package io.temporal.demo.docrouting.workflow;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.demo.docrouting.model.CompleteTask;
import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.ErpConfirmation;
import io.temporal.demo.docrouting.model.RequestOutcome;
import io.temporal.demo.docrouting.model.ReassignTask;
import io.temporal.demo.docrouting.model.Route;
import io.temporal.demo.docrouting.model.TaskResult;
import io.temporal.demo.docrouting.workflow.SearchAttrs.Steps;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DocumentRequestWorkflowTest {
  static final String QUEUE = "test-queue";
  static final Duration SLA = Duration.ofSeconds(60);
  static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(2);

  TestWorkflowEnvironment env;
  WorkflowClient client;
  final FakeActivities fake = new FakeActivities();

  void start(boolean archive) {
    env = TestWorkflowEnvironment.newInstance();
    TestSearchAttributes.register(env);
    Worker worker = env.newWorker(QUEUE);
    WorkflowSettings settings = new WorkflowSettings(SLA, CALLBACK_TIMEOUT, archive);
    worker.registerWorkflowImplementationFactory(
        DocumentRequestWorkflow.class, () -> new DocumentRequestWorkflowImpl(settings));
    worker.registerActivitiesImplementations(fake);
    env.start();
    client = env.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    if (env != null) env.close();
  }

  static DocumentRequest doc(String id, String bu, String type, long cost) {
    return new DocumentRequest(id, bu, type, cost, "CC-10", "Dana");
  }

  DocumentRequestWorkflow begin(DocumentRequest doc) {
    DocumentRequestWorkflow wf = client.newWorkflowStub(DocumentRequestWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(QUEUE)
            .setWorkflowId(DocumentRequestWorkflow.workflowId(doc.documentId()))
            .build());
    WorkflowClient.start(wf::process, doc);
    return wf;
  }

  RequestOutcome result(DocumentRequestWorkflow wf) {
    return WorkflowStub.fromTyped(wf).getResult(RequestOutcome.class);
  }

  WorkflowExecutionDescription describe(DocumentRequestWorkflow wf) {
    return WorkflowStub.fromTyped(wf).describe();
  }

  String step(DocumentRequestWorkflow wf) {
    return describe(wf).getTypedSearchAttributes().get(SearchAttrs.STEP);
  }

  /** Lets the workflow run (with time skipping paused) until it reaches the given step. */
  void awaitStep(DocumentRequestWorkflow wf, String expected) throws InterruptedException {
    Instant giveUp = Instant.now().plusSeconds(10);
    while (!expected.equals(step(wf))) {
      if (Instant.now().isAfter(giveUp)) fail("never reached step " + expected + ", last was " + step(wf));
      Thread.sleep(50);
    }
  }

  // ---- routing and search attributes ---------------------------------------------------------

  @Test
  void decisionAndProgressAreSearchable() throws Exception {
    start(false);
    fake.route(Route.HUMAN_REVIEW, 7);
    var wf = begin(doc("sa1", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    var attrs = describe(wf).getTypedSearchAttributes();
    assertEquals("HumanReview", attrs.get(SearchAttrs.PATH));
    assertEquals("Equipment", attrs.get(SearchAttrs.BUSINESS_UNIT));
    assertEquals(7L, attrs.get(SearchAttrs.RULES_VERSION));
    assertEquals(3000L, attrs.get(SearchAttrs.COST));
    assertEquals("CC-10", attrs.get(SearchAttrs.COST_CENTER));
    assertEquals("invoice", attrs.get(SearchAttrs.DOC_TYPE));
    assertEquals(false, attrs.get(SearchAttrs.SLA_BREACHED));
    assertEquals(List.of("Classify", "EvaluateRules"), fake.calls());
    assertEquals(0, describe(wf).getWorkflowExecutionInfo().getMemo().getFieldsCount(),
        "everything the inbox shows is a search attribute");
  }

  // ---- Invoice -------------------------------------------------------------------------------

  @Test
  void invoiceWaitsForErpConfirmationThenNotifies() throws Exception {
    start(false);
    fake.route(Route.INVOICE, 1);
    var wf = begin(doc("b1", "Services", "invoice", 3000));
    awaitStep(wf, Steps.AWAIT_ERP_CONFIRMATION);
    assertFalse(fake.calls().contains("NotifyRequester"), "must not notify before the acknowledgement");
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertEquals(Route.INVOICE, outcome.path());
    assertEquals(List.of("Classify", "EvaluateRules", "PostInvoiceToErp", "NotifyRequester"), fake.calls());
    assertEquals(Steps.COMPLETED, step(wf));
  }

  /**
   * Activities carry no summary, so the Temporal UI timeline labels them with their PascalCase
   * type; timers have no type, so their summaries use the same PascalCase form.
   */
  @Test
  void timelineUsesPascalCaseNames() throws Exception {
    start(true);
    fake.route(Route.INVOICE, 1);
    var wf = begin(doc("t1", "Services", "invoice", 3000));
    awaitStep(wf, Steps.AWAIT_ERP_CONFIRMATION);
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    result(wf);

    var converter = DefaultDataConverter.newDefaultInstance();
    List<String> activities = new ArrayList<>();
    List<String> timers = new ArrayList<>();
    List<String> signals = new ArrayList<>();
    for (HistoryEvent e : client.fetchHistory(DocumentRequestWorkflow.workflowId("t1")).getEvents()) {
      String summary = e.hasUserMetadata() && e.getUserMetadata().hasSummary()
          ? converter.fromPayload(e.getUserMetadata().getSummary(), String.class, String.class)
          : null;
      switch (e.getEventType()) {
        case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
          assertNull(summary, "Activities are labelled by their type, not a summary");
          activities.add(e.getActivityTaskScheduledEventAttributes().getActivityType().getName());
        }
        case EVENT_TYPE_TIMER_STARTED -> timers.add(summary);
        case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED ->
            signals.add(e.getWorkflowExecutionSignaledEventAttributes().getSignalName());
        default -> {}
      }
    }
    assertEquals(List.of("Classify", "EvaluateRules", "PostInvoiceToErp", "NotifyRequester",
        "ArchiveDocument"), activities);
    assertEquals(List.of("WaitForErpConfirmation"), timers);
    assertEquals(List.of("ErpPostingConfirmed"), signals);
  }

  @Test
  void acknowledgementArrivingEarlyIsKept() {
    start(false);
    fake.route(Route.INVOICE, 1);
    var wf = begin(doc("b2", "Services", "invoice", 3000));
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    wf.erpPostingConfirmed(new ErpConfirmation("A-dup", "later")); // duplicate is ignored
    assertEquals(RequestOutcome.COMPLETED, result(wf).status());
    assertEquals(1, fake.calls().stream().filter("NotifyRequester"::equals).count());
  }

  @Test
  void missingErpConfirmationFailsTheStep() {
    start(false);
    fake.route(Route.INVOICE, 1);
    var wf = begin(doc("b3", "Services", "invoice", 3000));
    var e = assertThrows(WorkflowFailedException.class, () -> result(wf));
    assertEquals("CallbackTimeout", ((ApplicationFailure) e.getCause()).getType());
    assertEquals(Steps.AWAIT_ERP_CONFIRMATION, step(wf));
  }

  // ---- HumanReview ---------------------------------------------------------------------------

  String attr(DocumentRequestWorkflow wf, io.temporal.common.SearchAttributeKey<String> key) {
    return describe(wf).getTypedSearchAttributes().get(key);
  }

  long updatesInHistory(String documentId) {
    return client.fetchHistory(DocumentRequestWorkflow.workflowId(documentId)).getEvents().stream()
        .filter(ev -> ev.hasWorkflowExecutionUpdateAcceptedEventAttributes()).count();
  }

  /** A Reassign is a Signal, so it returns before the workflow acts on it: wait for the next task. */
  void awaitTask(DocumentRequestWorkflow wf, String taskId) throws InterruptedException {
    Instant giveUp = Instant.now().plusSeconds(10);
    while (!taskId.equals(attr(wf, SearchAttrs.TASK_ID))) {
      if (Instant.now().isAfter(giveUp)) fail("task " + taskId + " never opened, last was " + attr(wf, SearchAttrs.TASK_ID));
      Thread.sleep(50);
    }
  }

  @Test
  void taskOpensForTheAssigneeTheRulesNamed() throws Exception {
    start(false);
    fake.route(Route.HUMAN_REVIEW, 1, "Dana");
    var wf = begin(doc("h1", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    assertEquals("Dana", attr(wf, SearchAttrs.ASSIGNEE));
    assertEquals("h1-t1", attr(wf, SearchAttrs.TASK_ID));
    var deadline = describe(wf).getTypedSearchAttributes().get(SearchAttrs.SLA_DEADLINE);
    assertNotNull(deadline, "the inbox sorts and counts down on SlaDeadline");
  }

  @Test
  void completingTheTaskEndsTheRequest() throws Exception {
    start(false);
    fake.route(Route.HUMAN_REVIEW, 1);
    var wf = begin(doc("h2", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    TaskResult r = wf.complete(new CompleteTask("h2-t1", "looks fine"));
    assertEquals("h2-t1", r.taskId());
    assertEquals("Completed by Alice", r.message());
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertEquals(Route.HUMAN_REVIEW, outcome.path());
    assertEquals("Completed by Alice", outcome.detail());
    assertEquals(List.of("Classify", "EvaluateRules", "NotifyRequester"), fake.calls(),
        "no ERP posting, budget or purchase order on this path");
    assertEquals(List.of("completed by Alice"), fake.notifications);
    assertEquals(Steps.COMPLETED, step(wf));
  }

  @Test
  void reassigningOpensANewTaskForTheNamedPerson() throws Exception {
    start(false);
    fake.route(Route.HUMAN_REVIEW, 1);
    var wf = begin(doc("h3", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);

    wf.reassign(new ReassignTask("h3-t1", "Bob", "on leave"));
    awaitTask(wf, "h3-t2");
    assertEquals("Bob", attr(wf, SearchAttrs.ASSIGNEE));
    assertEquals(Steps.HUMAN_TASK, step(wf));

    wf.reassign(new ReassignTask("h3-t2", "Priya Shah", ""));
    awaitTask(wf, "h3-t3");
    assertEquals("Priya Shah", attr(wf, SearchAttrs.ASSIGNEE));

    assertEquals("Completed by Priya Shah", wf.complete(new CompleteTask("h3-t3", "")).message());
    assertEquals("Completed by Priya Shah", result(wf).detail());
    assertEquals(List.of("Classify", "EvaluateRules", "NotifyRequester"), fake.calls());
  }

  @Test
  void staleOrWrongTaskIsRefusedWithoutTouchingHistory() throws Exception {
    start(false);
    fake.route(Route.HUMAN_REVIEW, 1);
    var wf = begin(doc("h4", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);

    var wrong = assertThrows(WorkflowUpdateException.class, () -> wf.complete(new CompleteTask("nope", "")));
    assertTrue(wrong.getCause().getMessage().contains("no longer open"), wrong.getCause().getMessage());

    wf.reassign(new ReassignTask("h4-t1", "Bob", ""));
    awaitTask(wf, "h4-t2");
    // A click on Alice's task after it moved to Bob is stale, and names the open task.
    var stale = assertThrows(WorkflowUpdateException.class, () -> wf.complete(new CompleteTask("h4-t1", "")));
    assertTrue(stale.getCause().getMessage().contains("h4-t2"), stale.getCause().getMessage());

    assertEquals(0, updatesInHistory("h4"), "refused updates must not be written to history");
    assertEquals("Bob", attr(wf, SearchAttrs.ASSIGNEE));
  }

  @Test
  void slaBreachMarksTheTaskAndReassigningRestartsIt() throws Exception {
    start(false);
    fake.route(Route.HUMAN_REVIEW, 1);
    var wf = begin(doc("h6", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    env.sleep(SLA.plusSeconds(5));
    assertEquals(true, describe(wf).getTypedSearchAttributes().get(SearchAttrs.SLA_BREACHED));
    assertEquals(Steps.HUMAN_TASK, step(wf));

    wf.reassign(new ReassignTask("h6-t1", "Bob", "late; over to you"));
    awaitTask(wf, "h6-t2");
    assertEquals(false, describe(wf).getTypedSearchAttributes().get(SearchAttrs.SLA_BREACHED));
    env.sleep(SLA.plusSeconds(5));
    assertEquals(true, describe(wf).getTypedSearchAttributes().get(SearchAttrs.SLA_BREACHED), "Bob's task has its own SLA");

    wf.complete(new CompleteTask("h6-t2", "late, but done"));
    assertEquals(RequestOutcome.COMPLETED, result(wf).status());
  }

  @Test
  void v2ArchivesAfterTheTaskIsCompleted() throws Exception {
    start(true);
    fake.route(Route.HUMAN_REVIEW, 1);
    var wf = begin(doc("h7", "Equipment", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    wf.complete(new CompleteTask("h7-t1", ""));
    result(wf);
    assertEquals(List.of("Classify", "EvaluateRules", "NotifyRequester", "ArchiveDocument"), fake.calls());
    assertEquals(Steps.COMPLETED, step(wf));
  }

  // ---- PurchaseOrder -------------------------------------------------------------------------

  @Test
  void purchaseOrderRunsChecksTasksAndFinalAction() throws Exception {
    start(false);
    fake.route(Route.PURCHASE_ORDER, 1);
    var wf = begin(doc("c1", "Equipment", "purchase-order", 800));
    assertEquals(RequestOutcome.COMPLETED, result(wf).status());
    assertEquals(List.of("Classify", "EvaluateRules",
        "ReserveBudget", "SendPurchaseOrder", "NotifyRequester"), fake.calls());
  }

  @Test
  void supplierRejectionReleasesTheBudget() throws Exception {
    start(false);
    fake.route(Route.PURCHASE_ORDER, 1);
    fake.supplierRejects = true;
    var wf = begin(doc("c2", "Equipment", "purchase-order", 800));
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.REJECTED, outcome.status());
    List<String> tail = fake.calls().subList(fake.calls().size() - 3, fake.calls().size());
    assertEquals(List.of("ReserveBudget", "SendPurchaseOrder", "ReleaseBudget"), tail);
    assertEquals(Steps.REJECTED, step(wf));
  }

  @Test
  void unknownCostCenterFailsTheRequestAtReserveBudgetWithNothingToUndo() {
    start(false);
    fake.route(Route.PURCHASE_ORDER, 1);
    fake.costCenterUnknown = true;
    var wf = begin(doc("c3", "Equipment", "purchase-order", 800));
    var e = assertThrows(WorkflowFailedException.class, () -> result(wf));
    assertTrue(e.getCause().getCause().getMessage().contains("unknown cost center"), e.getCause().getCause().getMessage());
    assertEquals(Steps.RESERVE_BUDGET, step(wf));
    assertEquals(1, fake.calls().stream().filter("ReserveBudget"::equals).count(), "422 must not be retried");
    assertFalse(fake.calls().contains("ReleaseBudget"), "nothing was reserved, so nothing is released");
    assertFalse(fake.calls().contains("SendPurchaseOrder"));
  }

  // ---- versions --------------------------------------------------------------------------------

  @Test
  void v1HasNoArchiveStep() {
    start(false);
    fake.route(Route.INVOICE, 1);
    var wf = begin(doc("v1", "Services", "invoice", 3000));
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    result(wf);
    assertFalse(fake.calls().contains("ArchiveDocument"));
  }

  @Test
  void v2ArchivesEveryCompletedRequest() {
    start(true);
    fake.route(Route.INVOICE, 1);
    var wf = begin(doc("v2", "Services", "invoice", 3000));
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    result(wf);
    assertEquals("ArchiveDocument", fake.calls().get(fake.calls().size() - 1));
    assertEquals(Steps.COMPLETED, step(wf));
  }
}
