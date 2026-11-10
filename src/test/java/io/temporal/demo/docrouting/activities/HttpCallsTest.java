package io.temporal.demo.docrouting.activities;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.temporal.failure.ApplicationFailure;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpCallsTest {
  static Javalin stub;
  final HttpCalls calls = new HttpCalls();

  @BeforeAll
  static void start() {
    stub = Javalin.create(c -> c.showJavalinBanner = false)
        .post("/status/{code}", ctx -> {
          int code = Integer.parseInt(ctx.pathParam("code"));
          ctx.status(code).json(java.util.Map.of("error", "stub says " + code));
        })
        .post("/slow", ctx -> {
          Thread.sleep(6000);
          ctx.result("{}");
        })
        .start(0);
  }

  @AfterAll
  static void stop() {
    stub.stop();
  }

  String url(String path) {
    return "http://localhost:" + stub.port() + path;
  }

  @Test
  void success() {
    assertEquals("stub says 200", calls.post(url("/status/200"), java.util.Map.of(), "k").get("error").asText());
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 408, 429})
  void transientStatusesAreRetryable(int status) {
    var f = assertThrows(ApplicationFailure.class, () -> calls.post(url("/status/" + status), java.util.Map.of(), "k"));
    assertEquals(HttpCalls.TRANSIENT, f.getType());
    assertFalse(f.isNonRetryable());
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 422})
  void clientErrorsAreNotRetryable(int status) {
    var f = assertThrows(ApplicationFailure.class, () -> calls.post(url("/status/" + status), java.util.Map.of(), "k"));
    assertEquals(HttpCalls.REJECTED, f.getType());
    assertTrue(f.isNonRetryable());
    assertTrue(f.getOriginalMessage().contains("stub says " + status), f.getOriginalMessage());
  }

  @Test
  void connectionRefusedIsRetryable() throws Exception {
    int freePort;
    try (ServerSocket s = new ServerSocket(0)) {
      freePort = s.getLocalPort();
    }
    var f = assertThrows(ApplicationFailure.class,
        () -> calls.post("http://localhost:" + freePort + "/x", java.util.Map.of(), "k"));
    assertEquals(HttpCalls.TRANSIENT, f.getType());
    assertFalse(f.isNonRetryable());
  }

  @Test
  void timeoutIsRetryable() {
    var f = assertThrows(ApplicationFailure.class, () -> calls.post(url("/slow"), java.util.Map.of(), "k"));
    assertEquals(HttpCalls.TRANSIENT, f.getType());
  }
}
