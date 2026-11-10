package io.temporal.demo.docrouting.rules;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.demo.docrouting.model.Route;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesLoaderTest {
  @TempDir Path dir;
  Path file;
  RulesLoader loader;

  static final String V1 = """
      { "version": 1,
        "businessUnits": {
          "Services": { "humanReviewAbove": 5000, "firstAssignee": "Alice" },
          "Equipment": { "humanReviewAbove": 1000, "firstAssignee": "Bob" } } }
      """;

  @BeforeEach
  void setUp() throws IOException {
    file = dir.resolve("rules.json");
    Files.copy(Path.of("config/rules.default.json"), file);
    loader = new RulesLoader(file);
  }

  @Test
  void committedDefaultsAreValid() {
    assertEquals(1, loader.current().version());
    assertEquals("Alice", loader.current().businessUnits().get("Services").firstAssignee());
    assertEquals("Bob", loader.current().businessUnits().get("Equipment").firstAssignee());
    assertNull(loader.status().lastRejection());
  }

  @Test
  void sameCostRoutesDifferentlyPerBusinessUnit() {
    var services = loader.evaluate("Services", "invoice", 3000);
    var equipment = loader.evaluate("Equipment", "invoice", 3000);
    assertEquals(Route.INVOICE, services.path());
    assertEquals(Route.HUMAN_REVIEW, equipment.path());
    assertEquals(1, services.rulesVersion());
    assertTrue(equipment.reason().contains("1000"), equipment.reason());
  }

  @Test
  void eachPath() {
    assertEquals(Route.HUMAN_REVIEW, loader.evaluate("Services", "invoice", 12000).path());
    assertEquals(Route.INVOICE, loader.evaluate("Services", "invoice", 300).path());
    assertEquals(Route.PURCHASE_ORDER, loader.evaluate("Equipment", "purchase-order", 800).path());
  }

  @Test
  void documentTypeAloneChoosesBetweenInvoiceAndPurchaseOrder() {
    for (String unit : new String[] {"Services", "Equipment"}) {
      assertEquals(Route.PURCHASE_ORDER, loader.evaluate(unit, "purchase-order", 500).path(), unit);
      assertEquals(Route.INVOICE, loader.evaluate(unit, "invoice", 500).path(), unit);
    }
    var po = loader.evaluate("Equipment", "purchase-order", 800);
    assertEquals("Equipment: purchase-order documents go out as a purchase order (cost 800 is within 1000)", po.reason());
    var invoice = loader.evaluate("Services", "invoice", 300);
    assertEquals("Services: invoice documents are posted to the ERP (cost 300 is within 5000)", invoice.reason());
  }

  @Test
  void humanReviewGoesFirstToTheUnitsAssignee() throws IOException {
    Files.writeString(file, V1);
    var services = loader.evaluate("Services", "invoice", 12000);
    var equipment = loader.evaluate("Equipment", "invoice", 3000);
    assertEquals("Alice", services.assignee());
    assertEquals("Bob", equipment.assignee());
    assertEquals("Equipment: cost 3000 is above the human-review threshold 1000; first review goes to Bob", equipment.reason());
    assertNull(loader.evaluate("Services", "invoice", 300).assignee(), "only HumanReview has an assignee");
  }

  @Test
  void missingOrBlankFirstAssigneeKeepsLastValidVersion() throws IOException {
    loader.current();
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace(", \"firstAssignee\": \"Bob\"", ""));
    assertEquals(1, loader.current().version());
    assertTrue(loader.status().lastRejection().contains("Equipment.firstAssignee is required"), loader.status().lastRejection());

    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("\"Bob\"", "\" \""));
    assertEquals(1, loader.current().version());
    assertTrue(loader.status().lastRejection().contains("Equipment.firstAssignee is required"));
  }

  @Test
  void unknownBusinessUnitIsRejected() {
    var e = assertThrows(RulesLoader.UnknownBusinessUnitException.class, () -> loader.evaluate("BU9", "invoice", 1));
    assertTrue(e.getMessage().contains("BU9"));
  }

  @Test
  void liveEditTakesEffectOnNextEvaluation() throws IOException {
    assertEquals(Route.INVOICE, loader.evaluate("Services", "invoice", 3000).path());
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    var decision = loader.evaluate("Services", "invoice", 3000);
    assertEquals(Route.HUMAN_REVIEW, decision.path());
    assertEquals(2, decision.rulesVersion());
  }

  @Test
  void invalidJsonKeepsLastValidVersion() throws IOException {
    loader.current();
    Files.writeString(file, "{ \"version\": 2, ");
    assertEquals(1, loader.current().version());
    assertNotNull(loader.status().lastRejection());
    assertEquals(1, loader.status().version());
  }

  @Test
  void negativeThresholdKeepsLastValidVersion() throws IOException {
    loader.current();
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "-5"));
    assertEquals(1, loader.current().version());
    assertTrue(loader.status().lastRejection().contains("Services.humanReviewAbove"));
  }

  @Test
  void unknownDocumentTypeIsRefusedBeforeTheCostCheck() {
    var e = assertThrows(RulesLoader.UnknownDocumentTypeException.class,
        () -> loader.evaluate("Services", "contract", 500));
    assertTrue(e.getMessage().contains("contract"), e.getMessage());
    // Above the threshold too: it fails now rather than reaching a reviewer.
    assertThrows(RulesLoader.UnknownDocumentTypeException.class, () -> loader.evaluate("Services", "contract", 99000));
  }

  @Test
  void pathNamesRoundTrip() {
    for (var r : Route.values()) assertEquals(r, Route.fromValue(r.value()));
    assertNull(Route.fromValue("ErpPosting"));
  }

  @Test
  void rollbackByRestoringEarlierContent() throws IOException {
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    assertEquals(2, loader.current().version());
    Files.writeString(file, V1);
    var decision = loader.evaluate("Services", "invoice", 3000);
    assertEquals(1, decision.rulesVersion());
    assertEquals(Route.INVOICE, decision.path());
  }

  @Test
  void noValidRulesEverMeansNoDecision() throws IOException {
    Files.writeString(file, "not json");
    var fresh = new RulesLoader(file);
    assertThrows(IllegalStateException.class, fresh::current);
    assertNull(fresh.status().version());
    assertNotNull(fresh.status().lastRejection());
  }
}
