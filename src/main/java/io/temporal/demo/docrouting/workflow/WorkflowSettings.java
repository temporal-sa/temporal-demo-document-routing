package io.temporal.demo.docrouting.workflow;

import java.time.Duration;

/**
 * Per-worker settings handed to the Workflow implementation when the worker registers it, so the
 * Workflow never reads the environment itself.
 *
 * @param archiveDocuments v2 behavior: archive each document at the end of every completed path
 */
public record WorkflowSettings(Duration slaDuration, Duration callbackTimeout, boolean archiveDocuments) {}
