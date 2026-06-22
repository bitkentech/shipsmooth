package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LegacyDataTreeGuard}: the precise condition under which a legacy
 * {@code .agents/} shipsmooth data tree is detected, and the actionability of the message.
 */
class LegacyDataTreeGuardTest {

    @TempDir Path repoRoot;

    @Test
    void firesOnLegacyAgentsPlansDirectory() throws IOException {
        Files.createDirectories(repoRoot.resolve(".agents").resolve("plans"));
        assertThrows(StandaloneConfigException.class, () -> LegacyDataTreeGuard.check(repoRoot));
    }

    @Test
    void doesNotFireOnCleanRepo() {
        assertDoesNotThrow(() -> LegacyDataTreeGuard.check(repoRoot));
    }

    @Test
    void doesNotFireOnBareAgentsDirWithoutPlans() throws IOException {
        // .agents/ as the emerging agent-CONFIG convention (e.g. skills/, mcp) — not
        // a shipsmooth data tree. Must not trip the guard.
        Files.createDirectories(repoRoot.resolve(".agents").resolve("skills"));
        assertDoesNotThrow(() -> LegacyDataTreeGuard.check(repoRoot));
    }

    @Test
    void doesNotFireWhenAgentsPlansIsAFileNotADirectory() throws IOException {
        Files.createDirectories(repoRoot.resolve(".agents"));
        Files.writeString(repoRoot.resolve(".agents").resolve("plans"), "not a dir");
        assertDoesNotThrow(() -> LegacyDataTreeGuard.check(repoRoot));
    }

    @Test
    void messageIsActionable() throws IOException {
        Files.createDirectories(repoRoot.resolve(".agents").resolve("plans"));
        StandaloneConfigException ex = assertThrows(StandaloneConfigException.class,
                () -> LegacyDataTreeGuard.check(repoRoot));
        String msg = ex.getMessage();
        assertTrue(msg.contains(".agents"), "names the legacy folder");
        assertTrue(msg.contains(".shipsmooth"), "names the new folder");
        assertTrue(msg.contains("git mv"), "gives the concrete rename command");
    }
}
