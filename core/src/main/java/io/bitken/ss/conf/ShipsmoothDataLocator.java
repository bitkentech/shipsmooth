package io.bitken.ss.conf;

import java.io.File;
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

    public ShipsmoothDataLocator(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    // ── plan files ─────────────────────────────────────────────────────────────

    private static final String PLANS_DIR = ".agents/plans";
    private static final String PLAN_PREFIX = "plan-";
    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String TASKS_SUFFIX = "-tasks.xml";

    /** {@code .agents/plans/} — the directory holding all plan files. */
    public Path plansDir() {
        return repoRoot.resolve(PLANS_DIR);
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
