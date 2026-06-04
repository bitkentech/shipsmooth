package io.bitken.ss.workflow.integration;

public class IntegrationDefaults {
    // TODO: This default value for VERIFY_CMD is wrong
    public static final String VERIFY_CMD = "mvn -pl plugin-tasks-java test";

    public static final int MAX_LLM_ITERATIONS = 3;
    public static final int MAX_TOTAL_FAILURES = 1;
}
