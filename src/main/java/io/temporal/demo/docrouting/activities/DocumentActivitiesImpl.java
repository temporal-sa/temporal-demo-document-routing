package io.temporal.demo.docrouting.activities;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.RoutingDecision;
import io.temporal.demo.docrouting.rules.RulesLoader;
import io.temporal.failure.ApplicationFailure;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class DocumentActivitiesImpl implements DocumentActivities {
  private final String systemsUrl;
  private final String webUrl;
  private final RulesLoader rules;
  private final Duration delay;
  private final HttpCalls http = new HttpCalls();

  /**
   * @param systemsUrl where the mock systems run
   * @param webUrl where the ERP sends its confirmation callback
   * @param delay demo-only pause at the start of every activity, so steps are spaced out in the
   *     Temporal UI timeline instead of landing on top of each other
   */
  public DocumentActivitiesImpl(String systemsUrl, String webUrl, RulesLoader rules, Duration delay) {
    this.systemsUrl = systemsUrl;
    this.webUrl = webUrl;
    this.rules = rules;
    this.delay = delay;
  }

  @Override
  public ClassifyOutput classify(DocumentRequest doc) {
    pause(); // to slow the demo down
    // A stand-in for a real classifier: the scenario carries its type.
    String type = doc.docType() == null || doc.docType().isBlank() ? "invoice" : doc.docType();
    return new ClassifyOutput(type.toLowerCase());
  }

  @Override
  public RoutingDecision evaluateRules(EvaluateRulesInput input) {
    pause(); // to slow the demo down
    DocumentRequest doc = input.doc();
    try {
      return rules.evaluate(doc.businessUnit(), input.docType(), doc.cost());
    } catch (RulesLoader.UnknownBusinessUnitException | RulesLoader.UnknownDocumentTypeException e) {
      throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), HttpCalls.REJECTED);
    }
  }

  @Override
  public PostInvoiceToErpOutput postInvoiceToErp(DocumentRequest doc) {
    String postingId = post("/erp/invoices", doc, Map.of("callbackUrl", webUrl + "/callbacks/erp"))
        .path("postingId").asText();
    return new PostInvoiceToErpOutput(postingId);
  }

  @Override
  public void notifyRequester(NotifyRequesterInput input) {
    post("/notify", input.doc(), Map.of("detail", input.doc().requester() + ": " + input.message()));
  }

  @Override
  public void reserveBudget(DocumentRequest doc) {
    post("/tasks/reserve-budget", doc, Map.of("detail", "$" + doc.cost() + " from " + doc.costCenter()));
  }

  @Override
  public void releaseBudget(DocumentRequest doc) {
    post("/tasks/reserve-budget/undo", doc, Map.of("detail", "$" + doc.cost() + " to " + doc.costCenter()));
  }

  @Override
  public void sendPurchaseOrder(DocumentRequest doc) {
    post("/supplier-portal/purchase-orders", doc, Map.of());
  }

  @Override
  public void archiveDocument(DocumentRequest doc) {
    post("/archive", doc, Map.of("detail", doc.documentId()));
  }

  private com.fasterxml.jackson.databind.JsonNode post(String path, DocumentRequest doc, Map<String, Object> extra) {
    pause(); // to slow the demo down
    ActivityInfo info = Activity.getExecutionContext().getInfo();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("workflowId", info.getWorkflowId());
    body.put("documentId", doc.documentId());
    body.put("businessUnit", doc.businessUnit());
    body.put("costCenter", doc.costCenter());
    body.put("cost", doc.cost());
    body.putAll(extra);
    String idempotencyKey = info.getWorkflowId() + "/" + info.getActivityType();
    return http.post(systemsUrl + path, body, idempotencyKey);
  }

  /** Demo-only: real systems take time, and spaced-out steps are easier to follow on screen. */
  private void pause() {
    if (delay.isZero()) return;
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
