package io.temporal.demo.docrouting.web;

import io.temporal.demo.docrouting.Config;
import io.temporal.demo.docrouting.TemporalClients;
import io.temporal.demo.docrouting.rules.RulesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code make web}: the demo page on http://localhost:8080. Not a worker; it stays up when workers stop. */
public final class WebMain {
  private static final Logger log = LoggerFactory.getLogger(WebMain.class);

  public static void main(String[] args) {
    Config config = Config.fromEnv();
    WebServer server =
        new WebServer(config, TemporalClients.connect(config, "web"), new RulesLoader(config.rulesFile()))
            .start(config.webPort());
    log.info("Demo app on http://localhost:{} (Temporal UI {})", server.port(), config.temporalUiUrl());
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
  }
}
