package io.temporal.demo.docrouting.activities;

/** Output of the {@code PostInvoiceToErp} Activity: the ERP's posting ID. The confirmation arrives later as a Signal. */
public record PostInvoiceToErpOutput(String postingId) {}
