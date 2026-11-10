package io.temporal.demo.docrouting.model;

/**
 * The rules' answer for one evaluation, including which rules version produced it.
 *
 * @param assignee who gets the first human task; set only when {@code path} is HumanReview
 */
public record RoutingDecision(Route path, String reason, int rulesVersion, String assignee) {}
