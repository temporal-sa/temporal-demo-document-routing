package io.temporal.demo.docrouting;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/** Builds a Temporal client from {@link Config} only: no CLI profile, no config file. */
public final class TemporalClients {
  private TemporalClients() {}

  public static WorkflowClient connect(Config config, String identity) {
    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(config.temporalAddress()).build());
    return WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder()
            .setNamespace(config.temporalNamespace())
            .setIdentity(identity)
            .build());
  }
}
