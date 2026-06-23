package io.bitken.ss.cli.conf.ds;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects a legacy {@code .agents/} shipsmooth data tree.
 *
 * <p>plan-85 renames the data folder {@code .agents/} → {@code .shipsmooth/} with
 * <em>no</em> back-compat and <em>no</em> migration. The new code only ever looks for
 * {@code .shipsmooth/}; if it silently treated a repo carrying an old {@code .agents/}
 * tree as a clean in-repo project, the user's existing plan history would be stranded
 * under a name nothing reads anymore. {@link ProjectDataStoreResolver} uses this predicate
 * to surface that case as an {@link DataStoreResolution.Unresolvable} rather than guessing.
 *
 * <p>Detection keys on the shipsmooth-specific {@code .agents/plans/} subdirectory, not
 * a bare {@code .agents/} directory — the latter is becoming an ecosystem convention for
 * human-authored agent <em>config</em> and must not trip the guard.
 */
final class LegacyDataTreeGuard {

    private LegacyDataTreeGuard() {
    }

    /**
     * @return {@code true} if {@code repoRoot} carries a legacy {@code .agents/plans/}
     *         shipsmooth data tree
     */
    static boolean isLegacyDataTree(Path repoRoot) {
        return Files.isDirectory(repoRoot.resolve(".agents").resolve("plans"));
    }
}
