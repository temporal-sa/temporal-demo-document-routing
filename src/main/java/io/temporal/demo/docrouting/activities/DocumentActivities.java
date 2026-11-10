package io.temporal.demo.docrouting.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.RoutingDecision;

/**
 * Every step that touches the outside world. Side-effecting calls send an Idempotency-Key of
 * {@code <workflowId>/<activity type>}, which stays the same across retries and resets, so a
 * repeat never causes a second effect.
 *
 * <p>Each Activity takes a single input object, and each one with a result returns an output
 * object, so a field can be added to either later without changing the signature, and the
 * Temporal UI shows inputs and results with named fields.
 */
@ActivityInterface
public interface DocumentActivities {

  /** Returns the document type. */
  ClassifyOutput classify(DocumentRequest doc);

  /** Reads the current routing rules; the decision, with its rules version, lands in history. */
  RoutingDecision evaluateRules(EvaluateRulesInput input);

  /** Invoice: post it to the ERP. Returns the ERP's posting ID; the confirmation arrives later as a Signal. */
  PostInvoiceToErpOutput postInvoiceToErp(DocumentRequest doc);

  /** Every path: tell the requester the outcome. */
  void notifyRequester(NotifyRequesterInput input);

  /** PurchaseOrder: reserve the cost against its cost center. Fails without retry if the cost center is unknown. */
  void reserveBudget(DocumentRequest doc);

  /** PurchaseOrder compensation: release the budget reservation. */
  void releaseBudget(DocumentRequest doc);

  /** PurchaseOrder: send the purchase order to the supplier portal. Fails without retry if the supplier rejects it. */
  void sendPurchaseOrder(DocumentRequest doc);

  /** v2 only: archive the processed document. */
  void archiveDocument(DocumentRequest doc);
}
