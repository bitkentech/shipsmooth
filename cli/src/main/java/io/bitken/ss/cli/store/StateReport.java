package io.bitken.ss.cli.store;

import io.bitken.ss.cli.ResolutionJson;
import io.bitken.ss.cli.conf.ds.ProjectDataStore;
import io.bitken.ss.conf.ResolvedStateRoot;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import java.nio.file.Path;

/**
 * Shared "where does state live" reporting for the {@code store} commands ({@code info} and
 * {@code init}'s success output). Emits the {@code ready} shape — {@code mode}, the state
 * root, and the ready-to-read {@code plansDir} — as either a machine-readable JSON line or
 * human text. All output goes to stdout.
 */
final class StateReport {

    private StateReport() {
    }

    /** Print the ready/settled state report for {@code store} resolved at {@code repoRoot}. */
    static void printReady(Path repoRoot, ProjectDataStore store, boolean json) {
        Path stateRoot = store.stateRoot();
        String mode = store instanceof ProjectDataStore.InRepo ? "in-repo" : "external";
        // plansDir via the locator so the in-repo (.shipsmooth) vs external layout difference
        // stays owned by the single source of path truth, not re-derived here.
        Path plansDir = new ShipsmoothDataLocator(repoRoot, ResolvedStateRoot.of(stateRoot)).plansDir();

        if (json) {
            System.out.println(ResolutionJson.ready(mode, stateRoot, plansDir));
        } else {
            System.out.println("shipsmooth: " + mode + " state at " + stateRoot);
            System.out.println("plans: " + plansDir);
        }
    }
}
