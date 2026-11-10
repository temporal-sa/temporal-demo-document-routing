package io.temporal.demo.docrouting.web;

import io.temporal.demo.docrouting.model.DocumentRequest;
import java.util.List;

/** The one-click documents on the demo page. */
public final class Scenarios {
  private Scenarios() {}

  public record Scenario(String id, String label, String hint, String businessUnit, String docType,
      long cost, String costCenter, int count) {
    DocumentRequest document(String documentId) {
      return new DocumentRequest(documentId, businessUnit, docType, cost, costCenter, "Dana Ortiz");
    }
  }

  public static final List<Scenario> ALL = List.of(
      new Scenario("services-invoice-3000", "Services · invoice · $3,000", "Posted to the ERP", "Services", "invoice", 3000, "CC-10", 1),
      new Scenario("equipment-invoice-3000", "Equipment · invoice · $3,000", "Same cost, Equipment: human task", "Equipment", "invoice", 3000, "CC-20", 1),
      new Scenario("services-invoice-12000", "Services · invoice · $12,000", "Above the Services threshold: human task", "Services", "invoice", 12000, "CC-10", 1),
      new Scenario("equipment-po-800", "Equipment · purchase order · $800", "Purchase order: budget, then the supplier", "Equipment", "purchase-order", 800, "CC-30", 1),
      new Scenario("cc99-batch", "3 × cost center CC-99", "Purchase order: an unknown cost center", "Equipment", "purchase-order", 800, "CC-99", 3));

  public static Scenario find(String id) {
    return ALL.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
  }
}
