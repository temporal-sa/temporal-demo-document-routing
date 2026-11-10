package io.temporal.demo.docrouting.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowStub;
import io.temporal.demo.docrouting.Config;
import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.RequestOutcome;
import io.temporal.demo.docrouting.model.Route;
import io.temporal.demo.docrouting.rules.RulesLoader;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflow;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflowImpl;
import io.temporal.common.SearchAttributes;
import io.temporal.demo.docrouting.workflow.FakeActivities;
import io.temporal.demo.docrouting.workflow.SearchAttrs;
import io.temporal.demo.docrouting.workflow.TestSearchAttributes;
import io.temporal.demo.docrouting.workflow.WorkflowSettings;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {
  static final ObjectMapper JSON = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();

  @TempDir Path dir;
  TestWorkflowEnvironment env;
  WebServer web;
  final FakeActivities fake = new FakeActivities().route(Route.INVOICE, 1);

  @BeforeEach
  void setUp() throws Exception {
    Files.copy(Path.of("config/rules.default.json"), dir.resolve("rules.json"));
    Files.copy(Path.of("config/reference-data.default.json"), dir.resolve("reference-data.json"));
    env = TestWorkflowEnvironment.newInstance();
    TestSearchAttributes.register(env);

    Config config = Config.from(Map.of("DATA_DIR", dir.toString(), "SYSTEMS_URL", "http://localhost:1"));
    Worker worker = env.newWorker(config.taskQueue());
    worker.registerWorkflowImplementationFactory(DocumentRequestWorkflow.class,
        () -> new DocumentRequestWorkflowImpl(new WorkflowSettings(Duration.ofSeconds(60), Duration.ofMinutes(2), false)));
    worker.registerActivitiesImplementations(fake);
    env.start();

    web = new WebServer(config, env.getWorkflowClient(), new RulesLoader(config.rulesFile())).start(0);
  }

  @AfterEach
  void tearDown() {
    web.stop();
    env.close();
  }

  HttpResponse<String> call(String method, String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + web.port() + path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void sameDocumentTwiceStartsNothingNew() throws Exception {
    // Not on the page any more, but still the guarantee: the Workflow ID is the document ID.
    DocumentRequest doc = Scenarios.find("services-invoice-3000").document("DUP1");
    Map<String, Object> first = web.start(doc);
    assertEquals(false, first.get("duplicate"));
    String workflowId = (String) first.get("workflowId");
    String runId = env.getWorkflowClient().newUntypedWorkflowStub(workflowId).describe().getExecution().getRunId();

    Map<String, Object> refused = web.start(doc);
    assertEquals(true, refused.get("duplicate"));
    assertEquals(workflowId, refused.get("workflowId"));

    String runIdAfter = env.getWorkflowClient().newUntypedWorkflowStub(workflowId).describe().getExecution().getRunId();
    assertEquals(runId, runIdAfter, "no new execution may start for a duplicate");
  }

  @Test
  void erpCallbackBecomesASignalAndCompletesTheRequest() throws Exception {
    var res = call("POST", "/api/submit", "{\"scenario\":\"services-invoice-3000\"}");
    String workflowId = JSON.readTree(res.body()).get("results").get(0).get("workflowId").asText();

    var cb = call("POST", "/callbacks/erp",
        "{\"workflowId\":\"" + workflowId + "\",\"postingId\":\"A-1\",\"confirmedAt\":\"now\"}");
    assertEquals(200, cb.statusCode(), cb.body());

    RequestOutcome outcome = WorkflowStub.fromTyped(
        env.getWorkflowClient().newWorkflowStub(DocumentRequestWorkflow.class, workflowId)).getResult(RequestOutcome.class);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertTrue(fake.calls().contains("NotifyRequester"));
  }

  @Test
  void callbackForFinishedRequestIsAcknowledgedSoTheSystemStopsRetrying() throws Exception {
    var cb = call("POST", "/callbacks/erp", "{\"workflowId\":\"doc-nope\",\"postingId\":\"A-1\"}");
    assertEquals(200, cb.statusCode());
    assertEquals("IGNORED", JSON.readTree(cb.body()).get("status").asText());
  }

  @Test
  void batchScenarioStartsThreeRequests() throws Exception {
    var res = call("POST", "/api/submit", "{\"scenario\":\"cc99-batch\"}");
    assertEquals(200, res.statusCode());
    assertEquals(3, JSON.readTree(res.body()).get("results").size());
  }

  @Test
  void tasksCanBeReassignedThenCompleted() throws Exception {
    fake.route(Route.HUMAN_REVIEW, 1);
    var res = call("POST", "/api/submit", "{\"scenario\":\"services-invoice-12000\"}");
    String workflowId = JSON.readTree(res.body()).get("results").get(0).get("workflowId").asText();
    String documentId = workflowId.substring("doc-".length());

    // The test server has no list queries, so the inbox itself is checked in the rehearsal on a dev server.
    assertEquals("Alice", awaitTask(workflowId, documentId + "-t1").get(SearchAttrs.ASSIGNEE));
    assertEquals(12000L, awaitTask(workflowId, documentId + "-t1").get(SearchAttrs.COST));

    String t1 = "{\"workflowId\":\"" + workflowId + "\",\"taskId\":\"" + documentId + "-t1\"";
    var reassigned = call("POST", "/api/tasks/reassign", t1 + ",\"assignee\":\"Bob\",\"comment\":\"over to you\"}");
    assertEquals(200, reassigned.statusCode(), reassigned.body());
    assertEquals("Reassign to Bob", JSON.readTree(reassigned.body()).get("message").asText());
    assertEquals("Bob", awaitTask(workflowId, documentId + "-t2").get(SearchAttrs.ASSIGNEE));

    var stale = call("POST", "/api/tasks/complete", t1 + "}");
    assertEquals(409, stale.statusCode(), stale.body());
    assertTrue(stale.body().contains(documentId + "-t2"), stale.body());

    var done = call("POST", "/api/tasks/complete",
        "{\"workflowId\":\"" + workflowId + "\",\"taskId\":\"" + documentId + "-t2\",\"comment\":\"\"}");
    assertEquals(200, done.statusCode(), done.body());
    assertEquals("Completed by Bob", JSON.readTree(done.body()).get("message").asText());
  }

  @Test
  void reassigningAFinishedRequestIsNotFound() throws Exception {
    var res = call("POST", "/api/tasks/reassign", "{\"workflowId\":\"doc-nope\",\"taskId\":\"nope-t1\",\"assignee\":\"Bob\"}");
    assertEquals(404, res.statusCode(), res.body());
  }

  /** Polls the request's search attributes until the given task is open. */
  SearchAttributes awaitTask(String workflowId, String taskId) throws Exception {
    long giveUp = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < giveUp) {
      var attrs = env.getWorkflowClient().newUntypedWorkflowStub(workflowId).describe().getTypedSearchAttributes();
      if (taskId.equals(attrs.get(SearchAttrs.TASK_ID))) return attrs;
      Thread.sleep(100);
    }
    throw new AssertionError("task " + taskId + " never opened");
  }

  JsonNode rulesFile() throws Exception {
    return JSON.readTree(Files.readString(dir.resolve("rules.json")));
  }

  @Test
  void savingAThresholdWritesTheNextRulesVersion() throws Exception {
    var res = call("PUT", "/api/rules", "{\"units\":{\"Equipment\":{\"humanReviewAbove\":5000}}}");
    assertEquals(200, res.statusCode(), res.body());
    JsonNode view = JSON.readTree(res.body());
    assertEquals(2, view.get("version").asInt());
    assertEquals(5000, view.get("units").get("Equipment").get("humanReviewAbove").asLong());
    assertEquals("Bob", view.get("units").get("Equipment").get("firstAssignee").asText(), "unsent field is kept");
    assertEquals(5000, view.get("units").get("Services").get("humanReviewAbove").asLong(), "untouched unit keeps its threshold");

    JsonNode file = rulesFile();
    assertEquals(2, file.get("version").asInt());
    assertEquals(5000, file.get("businessUnits").get("Equipment").get("humanReviewAbove").asLong());
    assertEquals("Bob", file.get("businessUnits").get("Equipment").get("firstAssignee").asText());
    assertEquals(5000, file.get("businessUnits").get("Services").get("humanReviewAbove").asLong(), "other units are kept");
  }

  @Test
  void savingAFirstAssigneeWritesTheNextRulesVersion() throws Exception {
    var res = call("PUT", "/api/rules", "{\"units\":{\"Services\":{\"firstAssignee\":\" Carol \"}}}");
    assertEquals(200, res.statusCode(), res.body());
    JsonNode view = JSON.readTree(res.body());
    assertEquals(2, view.get("version").asInt());
    assertEquals("Carol", view.get("units").get("Services").get("firstAssignee").asText());
    assertEquals(5000, view.get("units").get("Services").get("humanReviewAbove").asLong(), "unsent field is kept");
    assertEquals("Bob", view.get("units").get("Equipment").get("firstAssignee").asText());

    JsonNode file = rulesFile();
    assertEquals("Carol", file.get("businessUnits").get("Services").get("firstAssignee").asText());
    assertEquals("Bob", file.get("businessUnits").get("Equipment").get("firstAssignee").asText());
  }

  @Test
  void invalidEditIsRefusedAndNothingIsWritten() throws Exception {
    String before = Files.readString(dir.resolve("rules.json"));
    var negative = call("PUT", "/api/rules", "{\"units\":{\"Services\":{\"humanReviewAbove\":-5}}}");
    assertEquals(400, negative.statusCode());
    assertTrue(negative.body().contains("must not be negative"), negative.body());

    var text = call("PUT", "/api/rules", "{\"units\":{\"Services\":{\"humanReviewAbove\":\"lots\"}}}");
    assertEquals(400, text.statusCode());
    assertTrue(text.body().contains("whole number"), text.body());

    var blank = call("PUT", "/api/rules", "{\"units\":{\"Services\":{\"firstAssignee\":\"  \"}}}");
    assertEquals(400, blank.statusCode());
    assertTrue(blank.body().contains("firstAssignee is required"), blank.body());

    var unknown = call("PUT", "/api/rules", "{\"units\":{\"BU9\":{\"humanReviewAbove\":10}}}");
    assertEquals(400, unknown.statusCode());

    var empty = call("PUT", "/api/rules", "{\"units\":{}}");
    assertEquals(400, empty.statusCode());

    assertEquals(before, Files.readString(dir.resolve("rules.json")));
  }

  @Test
  void resetRestoresDefaultRulesAndReferenceData() throws Exception {
    call("PUT", "/api/rules", "{\"units\":{\"Equipment\":{\"humanReviewAbove\":5000}}}");
    call("PUT", "/api/rules", "{\"units\":{\"Services\":{\"firstAssignee\":\"Carol\"}}}");
    Files.writeString(dir.resolve("reference-data.json"), "{\"costCenters\":[\"CC-10\",\"CC-99\"]}");
    assertEquals(3, rulesFile().get("version").asInt());

    var res = call("POST", "/api/reset-data", "");
    assertEquals(200, res.statusCode(), res.body());
    assertEquals(1, JSON.readTree(res.body()).get("rules").get("version").asInt());
    assertEquals(1, rulesFile().get("version").asInt());
    assertFalse(Files.readString(dir.resolve("reference-data.json")).contains("CC-99"));
  }

  @Test
  void controlProxyReportsMissingSystemsProcess() throws Exception {
    var res = call("PUT", "/api/control", "{\"erpDown\":true,\"supplierRejects\":false}");
    assertEquals(503, res.statusCode());
    assertTrue(res.body().contains("make systems"));
  }
}
