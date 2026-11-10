package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/**
 * Input to the {@code EvaluateRules} Activity.
 *
 * @param docType the type {@code Classify} returned
 */
public record EvaluateRulesInput(DocumentRequest doc, String docType) {}
