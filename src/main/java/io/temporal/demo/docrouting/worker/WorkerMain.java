package io.temporal.demo.docrouting.worker;

import io.temporal.client.WorkflowClient;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.demo.docrouting.Config;
import io.temporal.demo.docrouting.TemporalClients;
import io.temporal.demo.docrouting.activities.DocumentActivitiesImpl;
import io.temporal.demo.docrouting.rules.RulesLoader;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflow;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflowImpl;
import io.temporal.demo.docrouting.workflow.WorkflowSettings;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Temporal worker. Each worker joins one Worker Deployment with a Build ID chosen at start
 * ({@code make worker BUILD_ID=v2}); every Workflow is pinned to the version it started on, so v1
 * requests finish on v1 while new requests run on whichever version is current.
 */
public final class WorkerMain {
  private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

  public static void main(String[] args) {
    Config config = Config.fromEnv();
    WorkflowClient client = TemporalClients.connect(config, "worker-" + config.buildId());
    WorkerFactory factory = WorkerFactory.newInstance(client);

    Worker worker =
        factory.newWorker(
            config.taskQueue(),
            WorkerOptions.newBuilder()
                .setDeploymentOptions(
                    WorkerDeploymentOptions.newBuilder()
                        .setUseVersioning(true)
                        .setVersion(new WorkerDeploymentVersion(config.deploymentName(), config.buildId()))
                        .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
                        .build())
                .build());

    WorkflowSettings settings = settingsFor(config);
    worker.registerWorkflowImplementationFactory(
        DocumentRequestWorkflow.class, () -> new DocumentRequestWorkflowImpl(settings));
    worker.registerActivitiesImplementations(
        new DocumentActivitiesImpl(config.systemsUrl(), config.webUrl(), new RulesLoader(config.rulesFile()),
            config.activityDelay()));

    factory.start();
    log.info(
        "Worker {}.{} polling task queue {} (SLA {}, archive step {})",
        config.deploymentName(),
        config.buildId(),
        config.taskQueue(),
        settings.slaDuration(),
        settings.archiveDocuments() ? "on" : "off");
    DeploymentBootstrap.ensureCurrentVersion(client, config.deploymentName(), config.buildId());

    Runtime.getRuntime().addShutdownHook(new Thread(factory::shutdown));
  }

  /**
   * Demo stand-in for shipping new code: v1 has no archive step; every later Build ID adds one.
   * In a real release v2 would simply be a new build containing the extra step.
   */
  static WorkflowSettings settingsFor(Config config) {
    return new WorkflowSettings(
        config.slaDuration(), config.callbackTimeout(), !"v1".equals(config.buildId()));
  }
}
