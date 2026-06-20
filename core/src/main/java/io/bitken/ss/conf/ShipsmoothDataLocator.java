package io.bitken.ss.conf;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Registry of filesystem paths for all shipsmooth data under {@code .agents/}.
 *
 * <p>Single source of truth for path construction — no other class should
 * hardcode {@code .agents/} strings. Named "Locator" to anticipate a future
 * option to relocate the data tree outside the repo.
 */
public final class ShipsmoothDataLocator {

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
}
