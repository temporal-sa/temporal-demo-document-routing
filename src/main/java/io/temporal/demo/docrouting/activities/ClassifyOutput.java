package io.temporal.demo.docrouting.activities;

/** Output of the {@code Classify} Activity: the document type, in lower case. */
public record ClassifyOutput(String docType) {}
