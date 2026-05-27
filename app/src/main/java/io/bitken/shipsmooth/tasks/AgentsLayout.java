package io.bitken.shipsmooth.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Canonical path conventions for the {@code .agents/} layout inside a repo.
 *
 * <p>All path templates live here so callers don't reconstruct strings manually.
 */
public final class AgentsLayout {

    private final Path repoRoot;

    public AgentsLayout(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    // ── plan files ─────────────────────────────────────────────────────────────

    /** {@code .agents/plans/plan-{planId}-tasks.xml} */
    public File planTasksFile(int planId) {
        return repoRoot.resolve(".agents/plans/plan-" + planId + "-tasks.xml").toFile();
    }

    /** {@code .agents/plans/plan-{planId}.md} */
    public File planMarkdownFile(int planId) {
        return repoRoot.resolve(".agents/plans/plan-" + planId + ".md").toFile();
    }

    // ── worktree paths ─────────────────────────────────────────────────────────

    /** Relative path: {@code .agents/tasks/{taskId}} */
    public String worktreeRel(String taskId) {
        return ".agents/tasks/" + taskId;
    }

    /** Branch name: {@code agent-work/{taskId}} */
    public String agentBranch(String taskId) {
        return "agent-work/" + taskId;
    }

    // ── integration paths ──────────────────────────────────────────────────────

    /** Branch name: {@code integration/plan-{planId}} */
    public String integrationBranch(int planId) {
        return "integration/plan-" + planId;
    }

    /** Relative path: {@code .agents/integration/plan-{planId}} */
    public String integrationRel(int planId) {
        return ".agents/integration/plan-" + planId;
    }

    // ── ledger / object store ──────────────────────────────────────────────────

    /** {@code .agents/ledger.jsonl} */
    public Path ledgerPath() {
        return repoRoot.resolve(".agents/ledger.jsonl");
    }

    /** {@code .agents/objects/} */
    public Path objectStorePath() {
        return repoRoot.resolve(".agents/objects");
    }

    // ── bootstrap ─────────────────────────────────────────────────────────────

    /** Create {@code .agents/objects/} and {@code .agents/ledger.jsonl} if absent. */
    public void bootstrap() throws IOException {
        Files.createDirectories(objectStorePath());
        Path ledger = ledgerPath();
        if (!Files.exists(ledger)) {
            Files.createFile(ledger);
        }
    }
}
