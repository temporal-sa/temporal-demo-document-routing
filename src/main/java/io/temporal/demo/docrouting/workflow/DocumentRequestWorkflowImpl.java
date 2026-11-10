package io.temporal.demo.docrouting.workflow;

import static io.temporal.demo.docrouting.workflow.SearchAttrs.*;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.demo.docrouting.activities.DocumentActivities;
import io.temporal.demo.docrouting.activities.EvaluateRulesInput;
import io.temporal.demo.docrouting.activities.HttpCalls;
import io.temporal.demo.docrouting.activities.NotifyRequesterInput;
import io.temporal.demo.docrouting.model.CompleteTask;
import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.ErpConfirmation;
import io.temporal.demo.docrouting.model.ReassignTask;
import io.temporal.demo.docrouting.model.RequestOutcome;
import io.temporal.demo.docrouting.model.RoutingDecision;
import io.temporal.demo.docrouting.model.TaskResult;
import io.temporal.demo.docrouting.workflow.SearchAttrs.Steps;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Saga;
import io.temporal.workflow.TimerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

public class DocumentRequestWorkflowImpl implements DocumentRequestWorkflow {
  static final ActivityOptions ACTIVITY_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(10))
          .setRetryOptions(RetryOptions.newBuilder()
              .setMaximumInterval(Duration.ofSeconds(10))
              .setMaximumAttempts(20)
              .build())
          .build();

  private final WorkflowSettings settings;
  private final DocumentActivities activities =
      Workflow.newActivityStub(DocumentActivities.class, ACTIVITY_OPTIONS);

  // The open human task. The first goes to the assignee the rules named; each assignment is a new
  // task ID. Complete (an Update) is accepted only for the open one; Reassign (a Signal) is taken
  // as sent. The human-task loop acts on whichever arrives.
  private String openTaskId;
  private String assignee;
  private boolean completed;
  private String reassignTo;

  // The ERP's asynchronous confirmation, set by a Signal from the callback
  private ErpConfirmation erpConfirmation;

  public DocumentRequestWorkflowImpl(WorkflowSettings settings) {
    this.settings = settings;
  }

  @Override
  @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
  public RequestOutcome process(DocumentRequest doc) {
    Workflow.upsertTypedSearchAttributes(
        BUSINESS_UNIT.valueSet(doc.businessUnit()), COST.valueSet(doc.cost()), COST_CENTER.valueSet(doc.costCenter()));

    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.CLASSIFY));
    String docType = activities.classify(doc).docType();

    Workflow.upsertTypedSearchAttributes(DOC_TYPE.valueSet(docType), STEP.valueSet(Steps.EVALUATE_RULES));
    RoutingDecision route = activities.evaluateRules(new EvaluateRulesInput(doc, docType));
    Workflow.upsertTypedSearchAttributes(
        PATH.valueSet(route.path().value()), RULES_VERSION.valueSet((long) route.rulesVersion()));

    return switch (route.path()) {
      case HUMAN_REVIEW -> humanReview(doc, route);
      case INVOICE -> invoice(doc, route);
      case PURCHASE_ORDER -> purchaseOrder(doc, route);
    };
  }

  // ---- HumanReview: a person finishes the request, or hands it to someone else --------------

  private RequestOutcome humanReview(DocumentRequest doc, RoutingDecision route) {
    assignee = route.assignee();
    for (int n = 1; !completed; n++) {
      humanTask(doc.documentId() + "-t" + n);
      if (reassignTo != null) assignee = reassignTo;
    }

    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.NOTIFY));
    activities.notifyRequester(new NotifyRequesterInput(doc, "completed by " + assignee));
    return finish(doc, route, "Completed by " + assignee);
  }

  /** Opens one task for the current assignee, with its own SLA, and waits for Complete or Reassign. */
  private void humanTask(String taskId) {
    openTaskId = taskId;
    reassignTo = null;
    OffsetDateTime deadline =
        Instant.ofEpochMilli(Workflow.currentTimeMillis()).plus(settings.slaDuration()).atOffset(ZoneOffset.UTC);
    Workflow.upsertTypedSearchAttributes(
        STEP.valueSet(Steps.HUMAN_TASK),
        ASSIGNEE.valueSet(assignee),
        TASK_ID.valueSet(taskId),
        SLA_DEADLINE.valueSet(deadline),
        SLA_BREACHED.valueSet(false));

    CancellationScope sla = Workflow.newCancellationScope(() ->
        Workflow.newTimer(settings.slaDuration(), named("ReviewSla")).handle((ignored, canceled) -> {
          if (canceled == null) {
            Workflow.upsertTypedSearchAttributes(SLA_BREACHED.valueSet(true));
          }
          return null;
        }));
    sla.run();

    Workflow.await(() -> completed || reassignTo != null);
    sla.cancel();
  }

  @Override
  public void validateComplete(CompleteTask action) {
    requireOpenTask(action == null ? null : action.taskId());
  }

  @Override
  public TaskResult complete(CompleteTask action) {
    String taskId = openTaskId;
    openTaskId = null;
    completed = true;
    return new TaskResult(taskId, "Completed by " + assignee);
  }

  @Override
  public void reassign(ReassignTask action) {
    openTaskId = null;
    reassignTo = action.assignee();
  }

  private void requireOpenTask(String taskId) {
    if (openTaskId == null || !openTaskId.equals(taskId)) {
      throw new IllegalArgumentException(
          "Task " + taskId + " is no longer open" + (openTaskId == null ? "" : "; the open task is " + openTaskId));
    }
  }

  // ---- Invoice: ERP posting, asynchronous confirmation, notification ------------------------

  private RequestOutcome invoice(DocumentRequest doc, RoutingDecision route) {
    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.POST_TO_ERP));
    activities.postInvoiceToErp(doc);

    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.AWAIT_ERP_CONFIRMATION));
    if (!awaitWithTimer(settings.callbackTimeout(), "WaitForErpConfirmation", () -> erpConfirmation != null)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "The ERP did not confirm the posting within " + settings.callbackTimeout(), "CallbackTimeout");
    }

    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.NOTIFY));
    activities.notifyRequester(new NotifyRequesterInput(doc, "posted to the ERP, reference " + erpConfirmation.postingId()));
    return finish(doc, route, "Posted to the ERP, requester notified");
  }

  @Override
  public void erpPostingConfirmed(ErpConfirmation ack) {
    if (erpConfirmation == null) erpConfirmation = ack; // a repeated acknowledgement changes nothing
  }

  // ---- PurchaseOrder: budget reservation, purchase order, compensation -------------------------

  private RequestOutcome purchaseOrder(DocumentRequest doc, RoutingDecision route) {
    Saga saga = new Saga(new Saga.Options.Builder().build());

    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.RESERVE_BUDGET));
    activities.reserveBudget(doc);
    saga.addCompensation(activities::releaseBudget, doc);

    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.SEND_PURCHASE_ORDER));
    try {
      activities.sendPurchaseOrder(doc);
    } catch (ActivityFailure e) {
      Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.COMPENSATING));
      saga.compensate();
      if (isRejection(e)) {
        Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.REJECTED));
        return new RequestOutcome(RequestOutcome.REJECTED, route.path(), route.rulesVersion(),
            "The supplier portal rejected the purchase order; budget reservation released");
      }
      throw e;
    }
    activities.notifyRequester(new NotifyRequesterInput(doc, "purchase order sent to the supplier portal"));
    return finish(doc, route, "Budget reserved, purchase order sent to the supplier portal");
  }

  // ---- shared -------------------------------------------------------------------------------

  private RequestOutcome finish(DocumentRequest doc, RoutingDecision route, String detail) {
    if (settings.archiveDocuments()) {
      Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.ARCHIVE));
      activities.archiveDocument(doc);
    }
    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.COMPLETED));
    return new RequestOutcome(RequestOutcome.COMPLETED, route.path(), route.rulesVersion(), detail);
  }

  /**
   * Like {@code Workflow.await(timeout, condition)}, but the timer carries a summary, so the
   * Temporal UI timeline shows "WaitForErpConfirmation" (PascalCase, like the Activity types)
   * instead of a timer ID. Returns false if the timer fired first.
   */
  private static boolean awaitWithTimer(Duration timeout, String summary, Supplier<Boolean> condition) {
    @SuppressWarnings("unchecked")
    Promise<Void>[] timer = new Promise[1];
    CancellationScope scope = Workflow.newCancellationScope(() -> timer[0] = Workflow.newTimer(timeout, named(summary)));
    scope.run();
    Workflow.await(() -> condition.get() || timer[0].isCompleted());
    if (!condition.get()) return false;
    scope.cancel(); // the condition won: cancel the timer, as Workflow.await(timeout, ...) does
    return true;
  }

  private static TimerOptions named(String summary) {
    return TimerOptions.newBuilder().setSummary(summary).build();
  }

  private static boolean isRejection(ActivityFailure e) {
    return e.getCause() instanceof ApplicationFailure af && HttpCalls.REJECTED.equals(af.getType());
  }
}
