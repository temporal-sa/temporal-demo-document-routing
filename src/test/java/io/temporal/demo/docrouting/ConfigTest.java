package io.temporal.demo.docrouting;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

  @Test
  void defaultsMatchTheDesign() {
    Config c = Config.from(Map.of());
    assertEquals("localhost:7233", c.temporalAddress());
    assertEquals("default", c.temporalNamespace());
    assertEquals("document-routing", c.deploymentName());
    assertEquals("v1", c.buildId());
    assertEquals(Duration.ofSeconds(60), c.slaDuration());
    assertEquals(Duration.ofSeconds(4), c.erpConfirmDelay());
    assertEquals(Duration.ofMinutes(2), c.callbackTimeout());
    assertEquals(Duration.ofMillis(500), c.activityDelay());
    assertEquals("http://localhost:8080", c.webUrl());
    assertEquals("http://localhost:8090", c.systemsUrl());
  }

  @Test
  void durationsAcceptShortAndIsoForms() {
    Config c = Config.from(Map.of("SLA_DURATION", "20s", "ERP_CONFIRM_DELAY", "PT1S", "CALLBACK_TIMEOUT", "500ms"));
    assertEquals(Duration.ofSeconds(20), c.slaDuration());
    assertEquals(Duration.ofSeconds(1), c.erpConfirmDelay());
    assertEquals(Duration.ofMillis(500), c.callbackTimeout());
  }

  @Test
  void badDurationNamesTheVariable() {
    var e = assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of("SLA_DURATION", "soon")));
    assertTrue(e.getMessage().contains("SLA_DURATION"));
  }
}
