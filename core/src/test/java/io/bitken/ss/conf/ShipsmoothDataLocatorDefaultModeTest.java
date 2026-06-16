package io.bitken.ss.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan-82 Task 5: default (in-repo) mode — when no separate state root is given,
 * the two-root methods must place data and worktrees inside the project tree
 * exactly as before, i.e. the {@code separateState() == false} branch of
 * {@link ShipsmoothDataLocator#worktreeBase} / {@code integrationBase}.
 */
public class ShipsmoothDataLocatorDefaultModeTest {

    @TempDir
    Path repoRoot;

    @Test
    public void worktreesLiveInsideProjectTreeInDefaultMode() {
        // Single-root constructor => stateRoot == repoRoot => default mode.
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);

        Path taskWorktree = locator.worktreeBase("task-9");
        Path integrationWorktree = locator.integrationBase(82);

        assertEquals(repoRoot.resolve(".agents/tasks/task-9"), taskWorktree,
                "default-mode task worktree must sit at .agents/tasks/<id> inside the project");
        assertEquals(repoRoot.resolve(".agents/integration/plan-82"), integrationWorktree,
                "default-mode integration worktree must sit at .agents/integration/plan-<n> inside the project");
        assertTrue(taskWorktree.startsWith(repoRoot),
                "default-mode worktree must be inside the project tree");
    }

    @Test
    public void dataLivesUnderProjectRootInDefaultMode() {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);

        assertTrue(locator.ledgerPath().startsWith(repoRoot));
        assertTrue(locator.objectStorePath().startsWith(repoRoot));
        assertTrue(locator.planTasksFile(82).toPath().startsWith(repoRoot));
    }

    @Test
    public void bootstrapIsIdempotent() throws Exception {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);

        locator.bootstrap();
        assertTrue(java.nio.file.Files.exists(locator.ledgerPath()), "ledger created on first bootstrap");

        // Second call must not fail or recreate the existing ledger (the
        // ledger-already-exists branch).
        assertDoesNotThrow(locator::bootstrap);
        assertTrue(java.nio.file.Files.exists(locator.ledgerPath()));
    }
}
