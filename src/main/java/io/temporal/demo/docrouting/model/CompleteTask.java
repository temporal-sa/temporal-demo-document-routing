package io.temporal.demo.docrouting.model;

/** Finish the open human task, identified by its task ID. Credited to whoever it's assigned to. */
public record CompleteTask(String taskId, String comment) {}
