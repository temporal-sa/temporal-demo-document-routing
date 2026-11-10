package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code NotifyRequester} Activity: the outcome to tell the requester about. */
public record NotifyRequesterInput(DocumentRequest doc, String message) {}
