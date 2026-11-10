package io.temporal.demo.docrouting.workflow;

import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.ErpConfirmation;
import io.temporal.demo.docrouting.model.RequestOutcome;
import io.temporal.demo.docrouting.model.CompleteTask;
import io.temporal.demo.docrouting.model.ReassignTask;
import io.temporal.demo.docrouting.model.TaskResult;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One execution per document. The Workflow ID is {@code doc-<documentId>}, so the same document
 * can't be processed twice.
 */
@WorkflowInterface
public interface DocumentRequestWorkflow {

  static String workflowId(String documentId) {
    return "doc-" + documentId;
  }

  @WorkflowMethod
  RequestOutcome process(DocumentRequest doc);

  /** HumanReview: finish the open task, which ends the request. Refused if the task isn't open. */
  @UpdateMethod(name = "Complete")
  TaskResult complete(CompleteTask action);

  @UpdateValidatorMethod(updateName = "Complete")
  void validateComplete(CompleteTask action);

  /** HumanReview: hand the open task to someone else. */
  @SignalMethod(name = "Reassign")
  void reassign(ReassignTask action);

  /** Invoice: the ERP's confirmation of the posting, delivered by callback. */
  @SignalMethod(name = "ErpPostingConfirmed")
  void erpPostingConfirmed(ErpConfirmation ack);
}
