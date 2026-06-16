package io.bitken.ss.conf;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifies the {@link java.nio.file.Path} that is the <b>project</b> repo root —
 * the working tree of the user's repository, used for git operations and as the
 * attachment point for worktrees.
 *
 * <p>Distinguishes the project root from {@link StateRoot} so the two same-typed
 * {@code Path} bindings can coexist in the dependency graph.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface RepoRoot {
}
