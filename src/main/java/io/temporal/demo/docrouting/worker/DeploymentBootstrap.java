package io.temporal.demo.docrouting.worker;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse;
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo convenience: on a fresh dev server the Worker Deployment has no current version, so new
 * Workflows would have nowhere to go. The first worker to start makes its own version current.
 * Later versions never promote themselves; that's {@code make promote}.
 */
final class DeploymentBootstrap {
  private static final Logger log = LoggerFactory.getLogger(DeploymentBootstrap.class);

  private DeploymentBootstrap() {}

  static void ensureCurrentVersion(WorkflowClient client, String deploymentName, String buildId) {
    var stub = client.getWorkflowServiceStubs().blockingStub();
    String namespace = client.getOptions().getNamespace();
    Instant giveUp = Instant.now().plus(Duration.ofSeconds(30));

    while (Instant.now().isBefore(giveUp)) {
      try {
        DescribeWorkerDeploymentResponse described =
            stub.describeWorkerDeployment(
                DescribeWorkerDeploymentRequest.newBuilder()
                    .setNamespace(namespace)
                    .setDeploymentName(deploymentName)
                    .build());
        var routing = described.getWorkerDeploymentInfo().getRoutingConfig();
        if (routing.hasCurrentDeploymentVersion()
            && !routing.getCurrentDeploymentVersion().getBuildId().isEmpty()) {
          log.info(
              "Worker Deployment {} current version is {}",
              deploymentName,
              routing.getCurrentDeploymentVersion().getBuildId());
          return;
        }
        boolean registered =
            described.getWorkerDeploymentInfo().getVersionSummariesList().stream()
                .anyMatch(v -> v.getDeploymentVersion().getBuildId().equals(buildId));
        if (registered) {
          stub.setWorkerDeploymentCurrentVersion(
              SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                  .setNamespace(namespace)
                  .setDeploymentName(deploymentName)
                  .setBuildId(buildId)
                  .setConflictToken(described.getConflictToken())
                  .setIdentity("document-routing-worker-bootstrap")
                  .build());
          log.info("Worker Deployment {} had no current version; made {} current", deploymentName, buildId);
          return;
        }
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
          log.warn("Could not check Worker Deployment {}: {}", deploymentName, e.getStatus());
        }
      }
      sleep();
    }
    log.warn(
        "Worker Deployment {} still has no current version. Run: make promote BUILD_ID={}",
        deploymentName,
        buildId);
  }

  private static void sleep() {
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
