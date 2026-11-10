package io.temporal.demo.docrouting.workflow;

import io.temporal.activity.Activity;
import io.temporal.demo.docrouting.activities.ClassifyOutput;
import io.temporal.demo.docrouting.activities.DocumentActivities;
import io.temporal.demo.docrouting.activities.EvaluateRulesInput;
import io.temporal.demo.docrouting.activities.HttpCalls;
import io.temporal.demo.docrouting.activities.NotifyRequesterInput;
import io.temporal.demo.docrouting.activities.PostInvoiceToErpOutput;
import io.temporal.demo.docrouting.model.DocumentRequest;
import io.temporal.demo.docrouting.model.Route;
import io.temporal.demo.docrouting.model.RoutingDecision;
import io.temporal.failure.ApplicationFailure;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scriptable activities for workflow tests. Records every call by activity type, in order. */
public class FakeActivities implements DocumentActivities {
  public final List<String> calls = new CopyOnWriteArrayList<>();
  /** The message of each notifyRequester call, in order. */
  public final List<String> notifications = new CopyOnWriteArrayList<>();
  private volatile RoutingDecision decision;
  volatile boolean costCenterUnknown;
  volatile boolean supplierRejects;

  /** Routes as the rules would; a HumanReview task goes first to Alice. */
  public FakeActivities route(Route path, int version) {
    return route(path, version, path == Route.HUMAN_REVIEW ? "Alice" : null);
  }

  public FakeActivities route(Route path, int version, String assignee) {
    decision = new RoutingDecision(path, "test routes to " + path, version, assignee);
    return this;
  }

  public List<String> calls() {
    return Collections.unmodifiableList(calls);
  }

  private void record() {
    calls.add(Activity.getExecutionContext().getInfo().getActivityType());
  }

  @Override
  public ClassifyOutput classify(DocumentRequest doc) {
    record();
    return new ClassifyOutput(doc.docType());
  }

  @Override
  public RoutingDecision evaluateRules(EvaluateRulesInput input) {
    record();
    return decision;
  }

  @Override
  public PostInvoiceToErpOutput postInvoiceToErp(DocumentRequest doc) {
    record();
    return new PostInvoiceToErpOutput("A-test");
  }

  @Override
  public void notifyRequester(NotifyRequesterInput input) {
    record();
    notifications.add(input.message());
  }

  @Override
  public void reserveBudget(DocumentRequest doc) {
    record();
    if (costCenterUnknown) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Budget: unknown cost center " + doc.costCenter(), HttpCalls.REJECTED);
    }
  }

  @Override
  public void releaseBudget(DocumentRequest doc) {
    record();
  }

  @Override
  public void sendPurchaseOrder(DocumentRequest doc) {
    record();
    if (supplierRejects) {
      throw ApplicationFailure.newNonRetryableFailure("Supplier portal rejected the purchase order", HttpCalls.REJECTED);
    }
  }

  @Override
  public void archiveDocument(DocumentRequest doc) {
    record();
  }
}
