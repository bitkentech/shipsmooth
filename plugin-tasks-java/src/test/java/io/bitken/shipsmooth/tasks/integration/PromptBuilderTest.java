package io.bitken.shipsmooth.tasks.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PromptBuilderTest {

    @Test
    public void promptContainsVerifyScopeGuidance() {
        ResolverContext ctx = new ResolverContext(
                3, "Add YAML backend", "implement yaml storage", "abc123",
                "diff text", List.of("TaskStore.java"), null);

        String prompt = PromptBuilder.build("/tmp/wt", ctx);

        assertTrue(prompt.contains("narrow"), "Prompt should instruct LLM to narrow verify command");
        assertTrue(prompt.contains("verify"), "Prompt should mention verify");
    }

    @Test
    public void promptIncludesVerifyErrorWhenPresent() {
        ResolverContext ctx = new ResolverContext(
                2, "Add XML backend", "implement xml storage", "abc123",
                "diff text", List.of(), "compilation failed: error on line 5");

        String prompt = PromptBuilder.build("/tmp/wt", ctx);

        assertTrue(prompt.contains("compilation failed"), "Prompt should include verify error output");
        assertTrue(prompt.contains("narrow"), "Prompt should still include scope guidance even with verify error");
    }
}