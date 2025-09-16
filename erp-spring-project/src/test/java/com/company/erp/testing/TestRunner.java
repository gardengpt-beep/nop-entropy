package com.company.erp.testing;

/**
 * Simple entry point executed through the Maven exec plugin. The goal is to
 * keep the build deterministic without relying on the standard JUnit tooling,
 * which would require downloading external dependencies in this offline
 * environment.
 */
public final class TestRunner {

    private TestRunner() {
    }

    public static void main(String[] args) {
        ModelDrivenCrudFlowTest test = new ModelDrivenCrudFlowTest();
        try {
            test.productCrudFlowIsDrivenByModels();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            throw new AssertionError("ModelDrivenCrudFlowTest failed", failure);
        }
    }
}
