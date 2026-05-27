package io.bitken.ss.workflow.integration;

public interface SubagentRunner {
    /**
     * Triggers the Lead Agent to spawn a resolver subagent with the given prompt.
     * Blocks until the subagent has finished and the Lead Agent acknowledges.
     */
    void run(String prompt) throws Exception;
}