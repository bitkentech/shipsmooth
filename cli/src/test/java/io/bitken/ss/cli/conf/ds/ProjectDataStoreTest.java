package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDataStoreTest {

    @TempDir Path tmp;

    // InRepo: stateRoot is the repo root; init is a no-op
    @Test
    void inRepo_stateRootIsRepoRoot_initIsNoOp() throws IOException {
        var store = new ProjectDataStore.InRepo(tmp);
        store.init();
        assertEquals(tmp, store.stateRoot());
    }

    // Standalone: init creates + git-inits an absent state dir; stateRoot is stateDir
    @Test
    void standalone_initCreatesGitRepo() throws IOException {
        Path repoRoot = tmp.resolve("project");
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(repoRoot);

        var store = new ProjectDataStore.Standalone(repoRoot, stateDir);
        store.init();

        assertEquals(stateDir, store.stateRoot());
        assertTrue(Files.isDirectory(stateDir.resolve(".git")),
                "init() should git-init the state dir");
    }

    // Standalone: a pre-existing (non-repo) state dir gets git-inited rather than skipped
    @Test
    void standalone_initGitInitsExistingNonRepoDir() throws IOException {
        Path repoRoot = tmp.resolve("project");
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(repoRoot);
        Files.createDirectories(stateDir); // exists, but is not a git repo

        var store = new ProjectDataStore.Standalone(repoRoot, stateDir);
        store.init();

        assertTrue(Files.isDirectory(stateDir.resolve(".git")),
                "init() should git-init a state dir that exists but is not yet a repo");
    }

    // Standalone: when already a git repo, init is a no-op (fast path leaves it untouched)
    @Test
    void standalone_initIsNoOpWhenAlreadyRepo() throws IOException {
        Path repoRoot = tmp.resolve("project");
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(repoRoot);

        var store = new ProjectDataStore.Standalone(repoRoot, stateDir);
        store.init(); // first init creates the repo

        // Drop a sentinel inside .git; a second init must not touch it.
        Path sentinel = stateDir.resolve(".git").resolve("ss-sentinel");
        Files.writeString(sentinel, "keep me");

        store.init();

        assertTrue(Files.exists(sentinel),
                "second init() should take the fast path and not re-init the repo");
    }

    // Standalone: existing .shipsmooth/ in-repo state is a hard error (no mid-project switch)
    @Test
    void standalone_existingInRepoState_throws() throws IOException {
        Path repoRoot = tmp.resolve("project");
        Files.createDirectories(repoRoot.resolve(".shipsmooth").resolve("plans"));
        Path stateDir = tmp.resolve("state");

        var store = new ProjectDataStore.Standalone(repoRoot, stateDir);
        assertThrows(StandaloneConfigException.class, store::init);
    }

    // A bare .shipsmooth/ without the plans/ subtree must NOT trip the in-repo-state guard.
    @Test
    void standalone_bareShipsmoothDirWithoutPlans_doesNotThrow() throws IOException {
        Path repoRoot = tmp.resolve("project");
        Files.createDirectories(repoRoot.resolve(".shipsmooth"));
        Path stateDir = tmp.resolve("state");

        var store = new ProjectDataStore.Standalone(repoRoot, stateDir);
        assertDoesNotThrow(store::init);
    }
}
