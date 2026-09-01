package io.bitken.ss.conf;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Registry of filesystem paths for all shipsmooth data.
 *
 * <p>Single source of truth for path construction — no other class should
 * hardcode the data-folder name. In-repo mode keeps data under the tool-owned
 * {@code .shipsmooth/} folder in the project repo; standalone mode points
 * {@code stateRoot} at a dedicated directory that <em>is</em> the data root.
 */
public final class ShipsmoothDataLocator {

    private final Path repoRoot;
    private final Path stateRoot;

    /**
     * Single-root (default / in-repo) mode: data lives under the project repo, so the state
     * root <em>is</em> the repo root. The token is minted here (validating the repo root as a
     * state root) and handed to the two-root constructor.
     */
    public ShipsmoothDataLocator(Path repoRoot) {
        this(repoRoot, ResolvedStateRoot.of(repoRoot));
    }

    /**
     * Two-root ("separate repo") mode: {@code repoRoot} is the project repo
     * (git ops / worktree attachment); {@code stateRoot} owns the data tree
     * (plan files, etc.). The state root arrives as a {@link ResolvedStateRoot} token — proof
     * it was already validated — so this constructor does not re-check it; only the project
     * repo root is validated eagerly here (it must always exist).
     */
    public ShipsmoothDataLocator(Path repoRoot, ResolvedStateRoot stateRoot) {
        validateRoot("project", repoRoot);
        this.repoRoot = repoRoot;
        this.stateRoot = stateRoot.path();
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

    /** Tool-owned data folder used in in-repo mode (replaces the legacy {@code .agents/}). */
    private static final String DATA_DIR = ".shipsmooth";
    private static final String PLANS_SUBDIR = "plans";
    private static final String PLAN_PREFIX = "plan-";
    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String TASKS_SUFFIX = "-tasks.xml";

    /**
     * The owned-folder marker file, at the data root (PB-360). Its presence is a
     * recorded fact that shipsmooth created this folder, rather than a heuristic.
     */
    public static final String MANIFEST_FILE = "manifest.toml";

    /**
     * Root of the data tree. In in-repo mode ({@code repoRoot == stateRoot}) the data lives
     * under {@code <repoRoot>/.shipsmooth}; in standalone mode the dedicated {@code stateRoot}
     * <em>is</em> the data root, so {@code plans/} hangs directly off it with no dot-folder
     * segment.
     */
    private Path dataRoot() {
        return repoRoot.equals(stateRoot) ? stateRoot.resolve(DATA_DIR) : stateRoot;
    }

    /** {@code plans/} — the directory holding all plan files (under {@link #dataRoot()}). */
    public Path plansDir() {
        return dataRoot().resolve(PLANS_SUBDIR);
    }

    /** {@code manifest.toml} — the owned-folder marker, at the {@link #dataRoot()} (PB-360). */
    public Path manifestFile() {
        return dataRoot().resolve(MANIFEST_FILE);
    }

    /** {@code plans/plan-{planId}-tasks.xml} under the data root. */
    public File planTasksFile(int planId) {
        return plansDir().resolve(PLAN_PREFIX + planId + TASKS_SUFFIX).toFile();
    }

    /** {@code plans/plan-{planId}.md} under the data root. */
    public File planMarkdownFile(int planId) {
        return plansDir().resolve(PLAN_PREFIX + planId + MARKDOWN_SUFFIX).toFile();
    }

    /** Regex matching a plan markdown filename, capturing the plan id. */
    public Pattern planMarkdownPattern() {
        return Pattern.compile(Pattern.quote(PLAN_PREFIX) + "(\\d+)" + Pattern.quote(MARKDOWN_SUFFIX));
    }
}
