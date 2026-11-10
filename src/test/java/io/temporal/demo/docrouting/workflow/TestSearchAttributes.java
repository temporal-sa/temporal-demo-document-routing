package io.temporal.demo.docrouting.workflow;

import io.temporal.testing.TestWorkflowEnvironment;

/** Registers the demo's custom search attributes, as {@code make temporal} does for the dev server. */
public final class TestSearchAttributes {
  private TestSearchAttributes() {}

  public static void register(TestWorkflowEnvironment env) {
    for (var key : SearchAttrs.ALL) {
      env.registerSearchAttribute(key.getName(), key.getValueType());
    }
  }
}
