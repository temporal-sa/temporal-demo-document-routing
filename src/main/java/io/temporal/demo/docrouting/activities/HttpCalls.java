package io.temporal.demo.docrouting.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Every call to an external system goes through here, and this is where a failure decides
 * whether retrying could help.
 *
 * <ul>
 *   <li><b>Transient</b>, retried with backoff by the Activity's Retry Policy: connection refused,
 *       timeouts, 5xx, 408 and 429.
 *   <li><b>Rejected</b>, never retried, because the same input gets the same answer: any other
 *       4xx, such as 400, 401, 403, 404 or 422. The response body becomes the failure message.
 * </ul>
 */
public final class HttpCalls {
  public static final String TRANSIENT = "Transient";
  public static final String REJECTED = "Rejected";

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public JsonNode post(String url, Object body, String idempotencyKey) {
    HttpResponse<String> res;
    try {
      HttpRequest.Builder req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(4))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      if (idempotencyKey != null) req.header("Idempotency-Key", idempotencyKey);
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw ApplicationFailure.newFailure(describe(url) + " unreachable: " + e, TRANSIENT);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApplicationFailure.newFailure(describe(url) + " call interrupted", TRANSIENT);
    }
    return classify(url, res.statusCode(), res.body());
  }

  static JsonNode classify(String url, int status, String body) {
    if (status / 100 == 2) return parse(body);
    String message = describe(url) + " returned HTTP " + status + ": " + errorText(body);
    if (isRetryable(status)) throw ApplicationFailure.newFailure(message, TRANSIENT);
    throw ApplicationFailure.newNonRetryableFailure(message, REJECTED);
  }

  static boolean isRetryable(int status) {
    return status >= 500 || status == 408 || status == 429;
  }

  private static JsonNode parse(String body) {
    try {
      return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    } catch (IOException e) {
      return JSON.createObjectNode().put("raw", body);
    }
  }

  private static String errorText(String body) {
    JsonNode node = parse(body);
    return node.hasNonNull("error") ? node.get("error").asText() : String.valueOf(body);
  }

  private static String describe(String url) {
    return "POST " + URI.create(url).getPath();
  }
}
