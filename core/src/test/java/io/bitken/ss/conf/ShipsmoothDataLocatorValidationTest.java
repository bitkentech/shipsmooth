package io.bitken.ss.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan-82 Task 5/6: the two-root {@link ShipsmoothDataLocator} validates both
 * roots eagerly (in the constructor) and fails fast with a clear, named error
 * when a root is inaccessible — rather than silently resolving paths under a
 * bad root and failing obscurely later.
 */
public class ShipsmoothDataLocatorValidationTest {

    @TempDir
    Path good;

    @Test
    public void acceptsAccessibleRoots() {
        // Two real, writable directories construct fine (this is also the common
        // case for every existing test using TempDir / ".").
        assertDoesNotThrow(() -> new ShipsmoothDataLocator(good, good));
    }

    @Test
    public void rejectsNonExistentStateRoot() {
        Path missing = good.resolve("does-not-exist");
        InaccessibleRootException ex = assertThrows(InaccessibleRootException.class,
                () -> new ShipsmoothDataLocator(good, missing));
        assertTrue(ex.getMessage().contains(missing.toString()),
                "error must name the offending path");
    }

    @Test
    public void rejectsNonExistentRepoRoot() {
        Path missing = good.resolve("nope");
        assertThrows(InaccessibleRootException.class,
                () -> new ShipsmoothDataLocator(missing, good));
    }

    @Test
    public void rejectsRootThatIsAFileNotADirectory() throws Exception {
        Path file = good.resolve("a-file");
        Files.writeString(file, "x");
        assertThrows(InaccessibleRootException.class,
                () -> new ShipsmoothDataLocator(good, file));
    }

    @Test
    public void rejectsNullRoot() {
        // The project root is validated first, so a null repoRoot is the null path.
        assertThrows(InaccessibleRootException.class,
                () -> new ShipsmoothDataLocator(null, good));
    }
}
