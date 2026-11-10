package io.temporal.demo.docrouting.systems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock external systems for the demo, all in one process so stopping it is a real outage of every
 * one of them:
 *
 * <ul>
 *   <li>The ERP accepts an invoice posting at once and confirms it later, by calling back.
 *   <li>Budget reservation and its undo. A reservation is refused (422) when its cost center
 *       isn't in the reference data, read from a JSON file on every call.
 *   <li>The supplier portal accepts a purchase order, or rejects it when told to.
 *   <li>A notification service and an archive.
 * </ul>
 *
 * <p>Side-effecting calls honor an {@code Idempotency-Key} header: a repeat gets the first
 * response and causes no second effect. The store is in memory, so a restart forgets it.
 */
public final class SystemsServer {
  private static final Logger log = LoggerFactory.getLogger(SystemsServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  public record Settings(Duration erpConfirmDelay, Path referenceDataFile) {}

  /** Runtime fault toggles, changed from the web app during the demo. */
  public record Faults(boolean erpDown, boolean supplierRejects) {}

  /** One recorded side effect, in the order it happened. */
  public record Effect(String at, String system, String action, String workflowId, String detail) {}

  /** A first response, plus the callback to re-send if the same request arrives again. */
  private record Stored(int status, Object body, Runnable resendCallback) {
    Stored(int status, Object body) {
      this(status, body, null);
    }
  }

  private final Settings settings;
  private final Map<String, Stored> idempotency = new ConcurrentHashMap<>();
  private final List<Effect> effects = new ArrayList<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private volatile Faults faults = new Faults(false, false);
  private Javalin app;

  public SystemsServer(Settings settings) {
    this.settings = settings;
  }

  public SystemsServer start(int port) {
    app = Javalin.create(cfg -> cfg.showJavalinBanner = false);

    app.post("/erp/invoices", this::erpPostInvoice);
    app.post("/tasks/reserve-budget", this::reserveBudget);
    app.post("/tasks/reserve-budget/undo", ctx -> recordEffect(ctx, "Budget", "Release budget (undo)"));
    app.post("/supplier-portal/purchase-orders", this::sendPurchaseOrder);
    app.post("/notify", ctx -> recordEffect(ctx, "Notifications", "Notify requester"));
    app.post("/archive", ctx -> recordEffect(ctx, "Archive", "Archive document"));

    app.get("/control", ctx -> ctx.json(faults));
    app.put("/control", this::putControl);
    app.get("/notifications", ctx -> ctx.json(effectsWhere("Notifications")));
    app.get("/effects", ctx -> ctx.json(snapshotEffects()));
    app.get("/healthz", ctx -> ctx.result("ok"));

    app.start(port);
    return this;
  }

  public int port() {
    return app.port();
  }

  public void stop() {
    scheduler.shutdownNow();
    if (app != null) app.stop();
  }

  public Faults faults() {
    return faults;
  }

  // ---- ERP --------------------------------------------------------------------------------------

  private void erpPostInvoice(Context ctx) throws IOException {
    if (faults.erpDown()) {
      ctx.status(503).json(Map.of("error", "The ERP is unavailable"));
      return;
    }
    JsonNode body = body(ctx);
    String workflowId = text(body, "workflowId");
    String callbackUrl = text(body, "callbackUrl");
    respondOnce(ctx, () -> {
      String postingId = "ERP-" + shortId();
      addEffect("ERP", "Accept invoice posting", workflowId, postingId);
      Map<String, Object> ack = new LinkedHashMap<>();
      ack.put("workflowId", workflowId);
      ack.put("postingId", postingId);
      ack.put("confirmedAt", Instant.now().plus(settings.erpConfirmDelay()).toString());
      Runnable sendAck = () -> callBackLater(callbackUrl, ack, settings.erpConfirmDelay(), "ERP confirmation for " + workflowId);
      sendAck.run();
      return new Stored(202, Map.of("postingId", postingId, "status", "ACCEPTED"), sendAck);
    });
  }

  // ---- Budget -----------------------------------------------------------------------------------

  private void reserveBudget(Context ctx) throws IOException {
    JsonNode body = body(ctx);
    // Checked before the idempotency store, so a refusal isn't remembered: once the cost center
    // is added to the reference data, a retry or reset with the same key reserves the budget.
    String problem = costCenterProblem(text(body, "costCenter"));
    if (problem != null) {
      ctx.status(422).json(Map.of("error", problem));
      return;
    }
    recordEffect(ctx, "Budget", "Reserve budget");
  }

  /** Returns null when the cost center is known, otherwise the reason it isn't. */
  private String costCenterProblem(String costCenter) {
    Set<String> known = referenceCostCenters();
    return known.contains(costCenter) ? null : "Budget: unknown cost center " + costCenter;
  }

  private Set<String> referenceCostCenters() {
    try {
      JsonNode root = JSON.readTree(Files.readString(settings.referenceDataFile()));
      Set<String> out = new java.util.HashSet<>();
      root.path("costCenters").forEach(n -> out.add(n.asText()));
      return out;
    } catch (IOException e) {
      // Unreadable reference data is the budget service's own outage: callers see a 500 and retry.
      throw new IllegalStateException("Cost-center reference data unavailable: " + e.getMessage(), e);
    }
  }

  // ---- Supplier portal --------------------------------------------------------------------------

  private void sendPurchaseOrder(Context ctx) throws IOException {
    if (faults.supplierRejects()) {
      JsonNode body = body(ctx);
      addEffect("Supplier portal", "Reject purchase order", text(body, "workflowId"), "");
      ctx.status(422).json(Map.of("error", "Supplier portal rejected the purchase order"));
      return;
    }
    recordEffect(ctx, "Supplier portal", "Accept purchase order");
  }

  // ---- shared ---------------------------------------------------------------------------------

  private void putControl(Context ctx) {
    faults = ctx.bodyAsClass(Faults.class);
    log.info("Faults now: ERP {}, supplier portal {}",
        faults.erpDown() ? "DOWN" : "up", faults.supplierRejects() ? "REJECTS" : "accepts");
    ctx.json(faults);
  }

  private void recordEffect(Context ctx, String system, String action) throws IOException {
    JsonNode body = body(ctx);
    String workflowId = text(body, "workflowId");
    String detail = body.path("detail").asText("");
    respondOnce(ctx, () -> {
      addEffect(system, action, workflowId, detail);
      return new Stored(200, Map.of("status", "DONE", "action", action));
    });
  }

  /**
   * Runs {@code once} the first time an Idempotency-Key is seen; a repeat replays its response
   * with no new effect, and re-sends its callback (as a real system re-acknowledges a duplicate).
   */
  private void respondOnce(Context ctx, Supplier<Stored> once) {
    String key = ctx.header("Idempotency-Key");
    if (key == null || key.isBlank()) {
      Stored stored = once.get();
      ctx.status(stored.status()).json(stored.body());
      return;
    }
    boolean[] first = {false};
    Stored stored = idempotency.computeIfAbsent(ctx.path() + "|" + key, k -> {
      first[0] = true;
      return once.get();
    });
    if (!first[0] && stored.resendCallback() != null) {
      log.info("Repeat of {} {}: no new effect, re-sending its callback", ctx.path(), key);
      stored.resendCallback().run();
    }
    ctx.status(stored.status()).json(stored.body());
  }

  /** POSTs the payload after a delay, retrying with backoff until the receiver answers 2xx. */
  private void callBackLater(String url, Object payload, Duration delay, String what) {
    scheduler.schedule(() -> deliver(url, payload, what, 1), delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void deliver(String url, Object payload, String what, int attempt) {
    try {
      HttpResponse<String> res = http.send(
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 == 2) {
        log.info("Delivered {} (attempt {})", what, attempt);
        return;
      }
      log.warn("Callback {} got HTTP {}: {}", what, res.statusCode(), res.body());
    } catch (IOException e) {
      log.warn("Callback {} failed: {}", what, e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    long backoffMs = Math.min(10_000, 500L << Math.min(attempt, 5));
    if (!scheduler.isShutdown()) {
      scheduler.schedule(() -> deliver(url, payload, what, attempt + 1), backoffMs, TimeUnit.MILLISECONDS);
    }
  }

  private synchronized void addEffect(String system, String action, String workflowId, String detail) {
    effects.add(new Effect(Instant.now().toString(), system, action, workflowId, detail));
    log.info("{}: {} for {} {}", system, action, workflowId, detail);
  }

  private synchronized List<Effect> snapshotEffects() {
    return List.copyOf(effects);
  }

  private synchronized List<Effect> effectsWhere(String system) {
    return effects.stream().filter(e -> e.system().equals(system)).toList();
  }

  private static JsonNode body(Context ctx) throws IOException {
    String raw = ctx.body();
    return raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
  }

  private static String text(JsonNode body, String field) {
    return body.path(field).asText("");
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
