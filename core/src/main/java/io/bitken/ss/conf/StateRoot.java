package io.bitken.ss.conf;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifies the {@link java.nio.file.Path} that is the shipsmooth <b>state</b>
 * root — the directory that owns the data tree (plan files, ledger, object
 * store). In default (in-repo) mode this equals the {@link RepoRoot}; in
 * "separate repo" mode it is a distinct directory so the project working tree
 * carries no shipsmooth trace.
 *
 * <p>Distinguishes the state root from {@link RepoRoot} so the two same-typed
 * {@code Path} bindings can coexist in the dependency graph.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface StateRoot {
}
