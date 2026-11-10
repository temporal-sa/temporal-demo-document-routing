package io.temporal.demo.docrouting.workflow;

import io.temporal.common.SearchAttributeKey;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Searchable progress, kept current by the Workflow at every meaningful step. These are what the
 * Temporal UI filters on: "how many are waiting on a person", "which are past SLA", and so on.
 */
public final class SearchAttrs {
  private SearchAttrs() {}

  public static final SearchAttributeKey<String> STEP = SearchAttributeKey.forKeyword("Step");
  public static final SearchAttributeKey<String> PATH = SearchAttributeKey.forKeyword("Path");
  public static final SearchAttributeKey<String> BUSINESS_UNIT = SearchAttributeKey.forKeyword("BusinessUnit");
  public static final SearchAttributeKey<Boolean> SLA_BREACHED = SearchAttributeKey.forBoolean("SlaBreached");
  public static final SearchAttributeKey<Long> RULES_VERSION = SearchAttributeKey.forLong("RulesVersion");
  public static final SearchAttributeKey<Long> COST = SearchAttributeKey.forLong("Cost");
  public static final SearchAttributeKey<String> COST_CENTER = SearchAttributeKey.forKeyword("CostCenter");
  public static final SearchAttributeKey<String> DOC_TYPE = SearchAttributeKey.forKeyword("DocType");

  // The open human task. Left as they were once the request moves on, so they say who finished it.
  public static final SearchAttributeKey<String> ASSIGNEE = SearchAttributeKey.forKeyword("Assignee");
  public static final SearchAttributeKey<String> TASK_ID = SearchAttributeKey.forKeyword("TaskId");
  public static final SearchAttributeKey<OffsetDateTime> SLA_DEADLINE = SearchAttributeKey.forOffsetDateTime("SlaDeadline");

  /** Every custom attribute, for registering them in tests. The Makefile registers the same set. */
  public static final List<SearchAttributeKey<?>> ALL = List.of(
      STEP, PATH, BUSINESS_UNIT, SLA_BREACHED, RULES_VERSION, COST, COST_CENTER, DOC_TYPE, ASSIGNEE, TASK_ID, SLA_DEADLINE);

  /** Values of {@link #STEP}. */
  public static final class Steps {
    private Steps() {}

    public static final String RECEIVED = "Received";
    public static final String CLASSIFY = "Classify";
    public static final String EVALUATE_RULES = "EvaluateRules";
    public static final String HUMAN_TASK = "HumanTask";
    public static final String POST_TO_ERP = "PostToErp";
    public static final String AWAIT_ERP_CONFIRMATION = "AwaitErpConfirmation";
    public static final String NOTIFY = "NotifyRequester";
    public static final String RESERVE_BUDGET = "ReserveBudget";
    public static final String SEND_PURCHASE_ORDER = "SendPurchaseOrder";
    public static final String COMPENSATING = "Compensating";
    public static final String ARCHIVE = "Archive";
    public static final String COMPLETED = "Completed";
    public static final String REJECTED = "Rejected";
  }
}
