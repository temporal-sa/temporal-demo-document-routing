package io.temporal.demo.docrouting.systems;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemsServerTest {
  static final ObjectMapper JSON = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();

  @TempDir Path dir;
  Path referenceData;
  SystemsServer systems;
  Javalin receiver;
  final LinkedBlockingQueue<JsonNode> callbacks = new LinkedBlockingQueue<>();
  final AtomicInteger failNextCallbacks = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    referenceData = dir.resolve("reference-data.json");
    Files.copy(Path.of("config/reference-data.default.json"), referenceData);
    systems = new SystemsServer(new SystemsServer.Settings(
        Duration.ofMillis(100), referenceData)).start(0);
    receiver = Javalin.create(c -> c.showJavalinBanner = false)
        .post("/callback", ctx -> {
          if (failNextCallbacks.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            ctx.status(503);
            return;
          }
          callbacks.add(JSON.readTree(ctx.body()));
        })
        .start(0);
  }

  @AfterEach
  void tearDown() {
    systems.stop();
    receiver.stop();
  }

  String base() {
    return "http://localhost:" + systems.port();
  }

  String callbackUrl() {
    return "http://localhost:" + receiver.port() + "/callback";
  }

  HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
    var b = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (key != null) b.header("Idempotency-Key", key);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode effects() throws Exception {
    return JSON.readTree(send("GET", "/effects", "", null).body());
  }

  @Test
  void sameIdempotencyKeyCausesOneEffectAndSameResponse() throws Exception {
    String body = "{\"workflowId\":\"doc-1\",\"callbackUrl\":\"" + callbackUrl() + "\"}";
    var first = send("POST", "/erp/invoices", body, "doc-1/submit/1");
    var second = send("POST", "/erp/invoices", body, "doc-1/submit/1");
    assertEquals(202, first.statusCode());
    assertEquals(first.body(), second.body());
    assertEquals(1, effects().size());
  }

  @Test
  void repeatedSubmissionReSendsErpConfirmationWithoutNewEffect() throws Exception {
    String body = "{\"workflowId\":\"doc-r\",\"callbackUrl\":\"" + callbackUrl() + "\"}";
    send("POST", "/erp/invoices", body, "doc-r/PostInvoiceToErp");
    assertNotNull(callbacks.poll(5, TimeUnit.SECONDS));
    send("POST", "/erp/invoices", body, "doc-r/PostInvoiceToErp");
    JsonNode again = callbacks.poll(5, TimeUnit.SECONDS);
    assertNotNull(again, "a replayed request must be re-acknowledged");
    assertEquals("doc-r", again.get("workflowId").asText());
    assertEquals(1, effects().size());
  }

  @Test
  void erpConfirmsLaterByCallback() throws Exception {
    var res = send("POST", "/erp/invoices",
        "{\"workflowId\":\"doc-2\",\"callbackUrl\":\"" + callbackUrl() + "\"}", "k2");
    assertEquals(202, res.statusCode());
    assertTrue(callbacks.isEmpty(), "acknowledgement must not be synchronous");
    JsonNode ack = callbacks.poll(5, TimeUnit.SECONDS);
    assertNotNull(ack);
    assertEquals("doc-2", ack.get("workflowId").asText());
    assertEquals(JSON.readTree(res.body()).get("postingId"), ack.get("postingId"));
  }

  @Test
  void callbackIsRetriedWhileReceiverFails() throws Exception {
    failNextCallbacks.set(2);
    send("POST", "/erp/invoices", "{\"workflowId\":\"doc-3\",\"callbackUrl\":\"" + callbackUrl() + "\"}", "k3");
    JsonNode ack = callbacks.poll(10, TimeUnit.SECONDS);
    assertNotNull(ack, "callback should be delivered after the receiver recovers");
    assertEquals("doc-3", ack.get("workflowId").asText());
  }

  @Test
  void erpDownReturns503() throws Exception {
    send("PUT", "/control", "{\"erpDown\":true,\"supplierRejects\":false}", null);
    var res = send("POST", "/erp/invoices", "{\"workflowId\":\"doc-4\",\"callbackUrl\":\"" + callbackUrl() + "\"}", "k4");
    assertEquals(503, res.statusCode());
    send("PUT", "/control", "{\"erpDown\":false,\"supplierRejects\":false}", null);
    assertEquals(202, send("POST", "/erp/invoices", "{\"workflowId\":\"doc-4\",\"callbackUrl\":\"" + callbackUrl() + "\"}", "k4").statusCode());
  }

  @Test
  void reservingAgainstUnknownCostCenterIs422UntilAddedToReferenceData() throws Exception {
    String body = "{\"workflowId\":\"doc-6\",\"costCenter\":\"CC-99\"}";
    var rejected = send("POST", "/tasks/reserve-budget", body, "r6");
    assertEquals(422, rejected.statusCode());
    assertTrue(rejected.body().contains("CC-99"));
    assertEquals(0, effects().size(), "a refused reservation reserves nothing");

    // Same key, as after a reset: the refusal wasn't remembered, so the reservation goes through.
    Files.writeString(referenceData, "{\"costCenters\":[\"CC-10\",\"CC-99\"]}");
    assertEquals(200, send("POST", "/tasks/reserve-budget", body, "r6").statusCode());
    assertEquals("Reserve budget", effects().get(0).get("action").asText());
  }

  @Test
  void supplierRejectsUnderToggleAndUndoIsRecorded() throws Exception {
    assertEquals(200, send("POST", "/tasks/reserve-budget", "{\"workflowId\":\"doc-7\",\"costCenter\":\"CC-10\"}", "r7").statusCode());
    send("PUT", "/control", "{\"erpDown\":false,\"supplierRejects\":true}", null);
    assertEquals(422, send("POST", "/supplier-portal/purchase-orders", "{\"workflowId\":\"doc-7\"}", "c7").statusCode());
    assertEquals(200, send("POST", "/tasks/reserve-budget/undo", "{\"workflowId\":\"doc-7\"}", "u7").statusCode());
    JsonNode effects = effects();
    assertEquals("Reserve budget", effects.get(0).get("action").asText());
    assertEquals("Reject purchase order", effects.get(1).get("action").asText());
    assertEquals("Release budget (undo)", effects.get(2).get("action").asText());
  }

  @Test
  void notificationsAreRecordedOncePerKey() throws Exception {
    send("POST", "/notify", "{\"workflowId\":\"doc-8\",\"detail\":\"processed\"}", "n8");
    send("POST", "/notify", "{\"workflowId\":\"doc-8\",\"detail\":\"processed\"}", "n8");
    assertEquals(1, JSON.readTree(send("GET", "/notifications", "", null).body()).size());
  }

  @Test
  void controlChangeAppliesToNextCall() throws Exception {
    assertEquals(200, send("POST", "/supplier-portal/purchase-orders", "{\"workflowId\":\"doc-9\"}", "c9a").statusCode());
    send("PUT", "/control", "{\"erpDown\":false,\"supplierRejects\":true}", null);
    JsonNode control = JSON.readTree(send("GET", "/control", "", null).body());
    assertTrue(control.get("supplierRejects").asBoolean());
    assertEquals(422, send("POST", "/supplier-portal/purchase-orders", "{\"workflowId\":\"doc-9\"}", "c9b").statusCode());
  }
}
