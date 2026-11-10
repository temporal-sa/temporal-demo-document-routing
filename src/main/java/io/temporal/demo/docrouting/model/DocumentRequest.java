package io.temporal.demo.docrouting.model;

/** A received document. {@code cost} is in whole dollars. */
public record DocumentRequest(
    String documentId,
    String businessUnit,
    String docType,
    long cost,
    String costCenter,
    String requester) {}
