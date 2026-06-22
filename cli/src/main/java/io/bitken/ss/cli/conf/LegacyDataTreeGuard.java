package io.bitken.ss.cli.conf;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects a legacy {@code .agents/} shipsmooth data tree and fails loudly.
 *
 * <p>plan-85 renames the data folder {@code .agents/} → {@code .shipsmooth/} with
 * <em>no</em> back-compat and <em>no</em> migration. The new code only ever looks for
 * {@code .shipsmooth/}; if it silently treated a repo carrying an old {@code .agents/}
 * tree as a clean in-repo project, the user's existing plan history would be stranded
 * under a name nothing reads anymore. This guard turns that silent stranding into an
 * actionable error telling the user to rename the folder by hand.
 *
 * <p>Detection keys on the shipsmooth-specific {@code .agents/plans/} subdirectory, not
 * a bare {@code .agents/} directory — the latter is becoming an ecosystem convention for
 * human-authored agent <em>config</em> and must not trip the guard.
 */
final class LegacyDataTreeGuard {

    private LegacyDataTreeGuard() {
    }

    /**
     * @throws StandaloneConfigException if {@code repoRoot} carries a legacy
     *         {@code .agents/plans/} shipsmooth data tree
     */
    static void check(Path repoRoot) {
        Path legacyPlans = repoRoot.resolve(".agents").resolve("plans");
        if (Files.isDirectory(legacyPlans)) {
            throw new StandaloneConfigException("""
                    Found a legacy .agents/ shipsmooth data tree in this repo.
                    shipsmooth no longer uses .agents/ — its data folder is now .shipsmooth/.
                    There is no automatic migration. To continue, rename the folder by hand:
                      git mv .agents .shipsmooth
                    (or move it, preserving the plans/ subtree), then re-run.""");
        }
    }
}
