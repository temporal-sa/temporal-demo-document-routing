package io.temporal.demo.docrouting;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Every setting comes from environment variables with local defaults. Nothing is read from a
 * Temporal CLI profile, so a default profile can't point the demo at a real Namespace.
 */
public record Config(
    String temporalAddress,
    String temporalNamespace,
    String taskQueue,
    String deploymentName,
    String buildId,
    Duration slaDuration,
    Duration erpConfirmDelay,
    Duration callbackTimeout,
    Duration activityDelay,
    int webPort,
    int systemsPort,
    String webUrl,
    String systemsUrl,
    String temporalUiUrl,
    Path dataDir,
    Path configDir) {

  public static Config fromEnv() {
    return from(System.getenv());
  }

  public static Config from(Map<String, String> env) {
    int webPort = Integer.parseInt(env.getOrDefault("WEB_PORT", "8080"));
    int systemsPort = Integer.parseInt(env.getOrDefault("SYSTEMS_PORT", "8090"));
    return new Config(
        env.getOrDefault("TEMPORAL_ADDRESS", "localhost:7233"),
        env.getOrDefault("TEMPORAL_NAMESPACE", "default"),
        env.getOrDefault("TEMPORAL_TASK_QUEUE", "document-routing"),
        env.getOrDefault("TEMPORAL_DEPLOYMENT_NAME", "document-routing"),
        env.getOrDefault("TEMPORAL_WORKER_BUILD_ID", "v1"),
        duration(env, "SLA_DURATION", "60s"),
        duration(env, "ERP_CONFIRM_DELAY", "4s"),
        duration(env, "CALLBACK_TIMEOUT", "2m"),
        duration(env, "ACTIVITY_DELAY", "500ms"),
        webPort,
        systemsPort,
        env.getOrDefault("WEB_URL", "http://localhost:" + webPort),
        env.getOrDefault("SYSTEMS_URL", "http://localhost:" + systemsPort),
        env.getOrDefault("TEMPORAL_UI_URL", "http://localhost:8233"),
        Path.of(env.getOrDefault("DATA_DIR", "data")),
        Path.of(env.getOrDefault("CONFIG_DIR", "config")));
  }

  public Path rulesFile() {
    return dataDir.resolve("rules.json");
  }

  public Path referenceDataFile() {
    return dataDir.resolve("reference-data.json");
  }

  public Path defaultRulesFile() {
    return configDir.resolve("rules.default.json");
  }

  public Path defaultReferenceDataFile() {
    return configDir.resolve("reference-data.default.json");
  }

  /** Parses "90s", "2m", "1h", "500ms", or an ISO-8601 duration such as "PT30S". */
  static Duration duration(Map<String, String> env, String key, String fallback) {
    String raw = env.getOrDefault(key, fallback).trim().toLowerCase();
    try {
      if (raw.startsWith("pt") || raw.startsWith("p")) return Duration.parse(raw.toUpperCase());
      if (raw.endsWith("ms")) return Duration.ofMillis(Long.parseLong(raw.substring(0, raw.length() - 2)));
      long n = Long.parseLong(raw.substring(0, raw.length() - 1));
      return switch (raw.charAt(raw.length() - 1)) {
        case 's' -> Duration.ofSeconds(n);
        case 'm' -> Duration.ofMinutes(n);
        case 'h' -> Duration.ofHours(n);
        default -> throw new IllegalArgumentException();
      };
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(key + " must be a duration like 60s, 2m or PT30S, got: " + raw);
    }
  }
}
