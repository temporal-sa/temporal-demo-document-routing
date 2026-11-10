package io.temporal.demo.docrouting.systems;

import io.temporal.demo.docrouting.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code make systems}: the mock external systems in their own process. */
public final class SystemsMain {
  private static final Logger log = LoggerFactory.getLogger(SystemsMain.class);

  public static void main(String[] args) {
    Config config = Config.fromEnv();
    SystemsServer server =
        new SystemsServer(
                new SystemsServer.Settings(
                    config.erpConfirmDelay(), config.referenceDataFile()))
            .start(config.systemsPort());
    log.info(
        "Mock systems on http://localhost:{} (reference data: {})",
        server.port(),
        config.referenceDataFile().toAbsolutePath());
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
  }
}
