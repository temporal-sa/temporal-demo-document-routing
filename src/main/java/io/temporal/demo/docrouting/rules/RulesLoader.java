package io.temporal.demo.docrouting.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.demo.docrouting.model.Route;
import io.temporal.demo.docrouting.model.RoutingDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business-unit routing rules, kept outside the workflow code in a JSON file.
 *
 * <p>The file is read on every evaluation, so editing it changes routing with no deploy. A file
 * that doesn't parse or validate never takes effect: the last valid rules stay in force and the
 * rejection is kept for the web app to show. Rolling back is just restoring an earlier file.
 */
public final class RulesLoader {
  private static final Logger log = LoggerFactory.getLogger(RulesLoader.class);
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /**
   * A unit's rules: the cost above which a person must review the request, and who gets that
   * review first. Within the threshold, the path follows from the document type alone
   * ({@link #pathFor}).
   */
  public record UnitRules(long humanReviewAbove, String firstAssignee) {}

  public record Rules(int version, Map<String, UnitRules> businessUnits) {}

  /** What the web app shows: the rules in force and the most recent rejected edit, if any. */
  public record Status(Integer version, String lastRejection) {}

  /** No entry for the document's business unit: a configuration problem, never fixed by retrying. */
  public static final class UnknownBusinessUnitException extends RuntimeException {
    public UnknownBusinessUnitException(String unit) {
      super("No routing rules for business unit " + unit);
    }
  }

  /** No path handles the document's type: fixed by correcting the document or adding a path. */
  public static final class UnknownDocumentTypeException extends RuntimeException {
    public UnknownDocumentTypeException(String docType) {
      super("No path for document type " + docType + "; only invoice and purchase-order are handled");
    }
  }

  private final Path file;
  private Rules lastValid;
  private String lastRejection;

  public RulesLoader(Path file) {
    this.file = file;
  }

  /** Reads and validates the file now; returns the rules in force afterwards. */
  public synchronized Rules current() {
    try {
      Rules candidate = JSON.readValue(Files.readString(file), Rules.class);
      List<String> problems = validate(candidate);
      if (problems.isEmpty()) {
        if (lastValid == null || lastValid.version() != candidate.version()) {
          log.info("Routing rules version {} in effect", candidate.version());
        }
        lastValid = candidate;
        lastRejection = null;
      } else {
        reject(String.join("; ", problems));
      }
    } catch (IOException e) {
      reject(file.getFileName() + " is not valid: " + firstLine(e.getMessage()));
    }
    if (lastValid == null) {
      throw new IllegalStateException("No valid routing rules yet: " + lastRejection);
    }
    return lastValid;
  }

  public synchronized Status status() {
    try {
      current();
    } catch (IllegalStateException ignored) {
      // status() reports the rejection instead of throwing
    }
    return new Status(lastValid == null ? null : lastValid.version(), lastRejection);
  }

  /**
   * Cost above the unit's human-review threshold goes to a person (HumanReview), starting with the
   * unit's first assignee; otherwise
   * invoices go to Invoice and purchase orders to PurchaseOrder. Any other document type is
   * refused, before it can reach a reviewer.
   */
  public RoutingDecision evaluate(String businessUnit, String docType, long cost) {
    Rules rules = current();
    UnitRules unit = rules.businessUnits().get(businessUnit);
    if (unit == null) throw new UnknownBusinessUnitException(businessUnit);
    Route path = pathFor(docType);

    if (cost > unit.humanReviewAbove()) {
      return new RoutingDecision(
          Route.HUMAN_REVIEW,
          "%s: cost %d is above the human-review threshold %d; first review goes to %s"
              .formatted(businessUnit, cost, unit.humanReviewAbove(), unit.firstAssignee()),
          rules.version(),
          unit.firstAssignee());
    }
    String costNote = "cost %d is within %d".formatted(cost, unit.humanReviewAbove());
    String action = path == Route.PURCHASE_ORDER ? "go out as a purchase order" : "are posted to the ERP";
    return new RoutingDecision(
        path, "%s: %s documents %s (%s)".formatted(businessUnit, docType, action, costNote), rules.version(), null);
  }

  /**
   * The path for a document whose cost is within the threshold. Never HumanReview: only the cost
   * threshold sends a request to a person.
   */
  static Route pathFor(String docType) {
    return switch (docType) {
      case "invoice" -> Route.INVOICE;
      case "purchase-order" -> Route.PURCHASE_ORDER;
      default -> throw new UnknownDocumentTypeException(docType);
    };
  }

  static List<String> validate(Rules rules) {
    List<String> problems = new ArrayList<>();
    if (rules.version() <= 0) problems.add("version must be a positive integer");
    if (rules.businessUnits() == null || rules.businessUnits().isEmpty()) {
      problems.add("at least one business unit is required");
      return problems;
    }
    rules.businessUnits().forEach((name, unit) -> {
      if (unit == null) {
        problems.add(name + " has no rules");
        return;
      }
      if (unit.humanReviewAbove() < 0) problems.add(name + ".humanReviewAbove must not be negative");
      if (unit.firstAssignee() == null || unit.firstAssignee().isBlank()) {
        problems.add(name + ".firstAssignee is required");
      } else if (!unit.firstAssignee().equals(unit.firstAssignee().strip())) {
        problems.add(name + ".firstAssignee must not start or end with spaces");
      }
    });
    return problems;
  }

  private void reject(String reason) {
    if (!reason.equals(lastRejection)) {
      log.warn("Rejected routing rules edit, keeping version {}: {}",
          lastValid == null ? "none" : lastValid.version(), reason);
    }
    lastRejection = reason;
  }

  /** Keeps the parser's reason and drops its location boilerplate. */
  private static String firstLine(String s) {
    if (s == null) return "unknown error";
    int nl = s.indexOf('\n');
    String line = nl < 0 ? s : s.substring(0, nl);
    for (String noise : new String[] {" (start marker", " at [Source"}) {
      int i = line.indexOf(noise);
      if (i > 0) line = line.substring(0, i);
    }
    return line;
  }
}
