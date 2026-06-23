package io.bitken.ss.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two-root {@link ShipsmoothDataLocator} validates the <em>project repo root</em>
 * eagerly in its constructor and fails fast with a clear, named error when it is
 * inaccessible. The <em>state root</em> arrives as a {@link ResolvedStateRoot} token —
 * already validated at mint time (see {@link ResolvedStateRootTest}) — so the locator does
 * not re-check it.
 */
public class ShipsmoothDataLocatorValidationTest {

    @TempDir
    Path good;

    @Test
    public void acceptsAccessibleRepoRootAndToken() {
        assertDoesNotThrow(() -> new ShipsmoothDataLocator(good, ResolvedStateRoot.of(good)));
    }

    @Test
    public void rejectsNonExistentRepoRoot() {
        Path missing = good.resolve("nope");
        InaccessibleRootException ex = assertThrows(InaccessibleRootException.class,
                () -> new ShipsmoothDataLocator(missing, ResolvedStateRoot.of(good)));
        assertTrue(ex.getMessage().contains(missing.toString()),
                "error must name the offending path");
    }

    @Test
    public void rejectsNullRepoRoot() {
        assertThrows(InaccessibleRootException.class,
                () -> new ShipsmoothDataLocator(null, ResolvedStateRoot.of(good)));
    }
}
