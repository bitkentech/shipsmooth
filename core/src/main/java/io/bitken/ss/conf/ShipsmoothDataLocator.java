package io.bitken.ss.conf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

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
    private final Path stateRoot;

    /** Single-root (default / in-repo) mode: data lives under the project repo. */
    public ShipsmoothDataLocator(Path repoRoot) {
        this(repoRoot, repoRoot);
    }

    /**
     * Two-root ("separate repo") mode: {@code repoRoot} is the project repo
     * (git ops / worktree attachment); {@code stateRoot} owns the data tree
     * (plan files, ledger, objects). When the two are equal, behavior is
     * identical to the legacy single-root mode.
     */
    public ShipsmoothDataLocator(Path repoRoot, Path stateRoot) {
        validateRoot("project", repoRoot);
        validateRoot("state", stateRoot);
        this.repoRoot = repoRoot;
        this.stateRoot = stateRoot;
    }

    /** Fail fast if a root does not point at an existing directory. */
    private static void validateRoot(String role, Path root) {
        if (root == null) {
            throw new InaccessibleRootException(role, root, "path is null");
        }
        if (!Files.exists(root)) {
            throw new InaccessibleRootException(role, root, "does not exist");
        }
        if (!Files.isDirectory(root)) {
            throw new InaccessibleRootException(role, root, "is not a directory");
        }
    }

    private boolean separateState() {
        return !stateRoot.equals(repoRoot);
    }

    // ── plan files ─────────────────────────────────────────────────────────────

    private static final String PLANS_DIR = ".agents/plans";
    private static final String PLAN_PREFIX = "plan-";
    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String TASKS_SUFFIX = "-tasks.xml";

    /** {@code .agents/plans/} — the directory holding all plan files (under the state root). */
    public Path plansDir() {
        return stateRoot.resolve(PLANS_DIR);
    }

    /** {@code .agents/plans/plan-{planId}-tasks.xml} */
    public File planTasksFile(int planId) {
        return plansDir().resolve(PLAN_PREFIX + planId + TASKS_SUFFIX).toFile();
    }

    /** {@code .agents/plans/plan-{planId}.md} */
    public File planMarkdownFile(int planId) {
        return plansDir().resolve(PLAN_PREFIX + planId + MARKDOWN_SUFFIX).toFile();
    }

    /** Regex matching a plan markdown filename, capturing the plan id. */
    public Pattern planMarkdownPattern() {
        return Pattern.compile(Pattern.quote(PLAN_PREFIX) + "(\\d+)" + Pattern.quote(MARKDOWN_SUFFIX));
    }

    // ── worktree paths ─────────────────────────────────────────────────────────

    /** Relative path: {@code .agents/tasks/{taskId}} */
    public String worktreeRel(String taskId) {
        return ".agents/tasks/" + taskId;
    }

    /**
     * Absolute filesystem location of the task worktree. In default mode this is
     * {@code <repoRoot>/.agents/tasks/{taskId}} (inside the project tree, as
     * today). In separate-repo mode the worktree is parked under the state root
     * so nothing appears inside the project tree — though it remains a worktree
     * of the project repo's git regardless.
     */
    public Path worktreeBase(String taskId) {
        return separateState()
                ? stateRoot.resolve("worktrees/tasks").resolve(taskId)
                : repoRoot.resolve(worktreeRel(taskId));
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

    /**
     * Absolute filesystem location of the integration worktree. Default mode:
     * {@code <repoRoot>/.agents/integration/plan-{planId}} (inside the project
     * tree). Separate-repo mode: parked under the state root, still a worktree
     * of the project repo's git.
     */
    public Path integrationBase(int planId) {
        return separateState()
                ? stateRoot.resolve("worktrees/integration").resolve("plan-" + planId)
                : repoRoot.resolve(integrationRel(planId));
    }

    // ── ledger / object store ──────────────────────────────────────────────────

    /** {@code .agents/ledger.jsonl} (under the state root). */
    public Path ledgerPath() {
        return stateRoot.resolve(".agents/ledger.jsonl");
    }

    /** {@code .agents/objects/} (under the state root). */
    public Path objectStorePath() {
        return stateRoot.resolve(".agents/objects");
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
