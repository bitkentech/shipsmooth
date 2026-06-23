package io.bitken.ss.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 12 — {@code ResolvedStateRoot} is the capability token that proves a state root has
 * been validated. Parse-don't-validate: the validation lives in the token's smart
 * constructor (the only place a bad state root is rejected), so the locator can consume the
 * token without re-checking the filesystem.
 */
public class ResolvedStateRootTest {

    @TempDir
    Path dir;

    @Test
    public void of_existingDirectory_mintsTokenCarryingThePath() {
        ResolvedStateRoot token = ResolvedStateRoot.of(dir);
        assertEquals(dir, token.path());
    }

    @Test
    public void of_nonExistentPath_isRejectedAtMinting() {
        Path missing = dir.resolve("nope");
        assertThrows(InaccessibleRootException.class, () -> ResolvedStateRoot.of(missing));
    }

    @Test
    public void locator_acceptsTheToken_andResolvesPathsUnderIt(@TempDir Path repoRoot) {
        // The locator now demands the token (not a bare Path) for the state root; it does not
        // re-validate. The token is the proof the state root is usable. With a distinct repo
        // root this is standalone mode: plans/ hangs directly off the token's state root.
        ResolvedStateRoot token = ResolvedStateRoot.of(dir);
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot, token);

        assertEquals(dir.resolve("plans").toFile(),
                locator.planTasksFile(7).getParentFile());
    }
}
