package io.temporal.demo.docrouting.model;

/** Hand the open human task, identified by its task ID, to {@code assignee}: any name will do. */
public record ReassignTask(String taskId, String assignee, String comment) {}
