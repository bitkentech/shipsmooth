package io.bitken.ss.workflow.integration;

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

    @Test
    public void promptContainsPreFlightCheck() {
        ResolverContext ctx = new ResolverContext(
                5, "Fix merge conflict", "fix it", "abc123",
                "diff text", List.of("Foo.java"), null);

        String prompt = PromptBuilder.build("/some/worktree/path", ctx);

        assertTrue(prompt.contains("ls /some/worktree/path"), "Prompt should include ls pre-flight command");
        assertTrue(prompt.contains("RESOLVER ABORT"), "Prompt should mention RESOLVER ABORT on missing worktree");
    }

    @Test
    public void promptInstructsAbsolutePathsForFileOps() {
        ResolverContext ctx = new ResolverContext(
                5, "Fix merge conflict", "fix it", "abc123",
                "diff text", List.of("Foo.java"), null);

        String prompt = PromptBuilder.build("/some/worktree/path", ctx);

        assertTrue(prompt.contains("absolute paths"), "Prompt should instruct use of absolute paths for file operations");
        assertFalse(prompt.contains("cwd"), "Prompt should not mention cwd as externally set");
    }
}