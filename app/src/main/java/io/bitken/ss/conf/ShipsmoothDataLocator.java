package io.bitken.ss.conf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Registry of filesystem paths for all shipsmooth data under {@code .agents/}.
 *
 * <p>Single source of truth for path construction — no other class should
 * hardcode {@code .agents/} strings. Named "Locator" to anticipate a future
 * option to relocate the data tree outside the repo.
 */
public final class ShipsmoothDataLocator {

    public static final List<String> GITIGNORE_ENTRIES = List.of(
        ".agents/tasks/*",
        ".agents/integration/*",
        ".agents/objects/",
        ".agents/ledger.jsonl"
    );

    private final Path repoRoot;

    public ShipsmoothDataLocator(Path repoRoot) {
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
