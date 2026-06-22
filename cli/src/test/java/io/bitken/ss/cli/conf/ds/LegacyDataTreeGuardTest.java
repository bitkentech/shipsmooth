package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LegacyDataTreeGuard#isLegacyDataTree}: the precise condition under
 * which a legacy {@code .agents/} shipsmooth data tree is detected.
 */
class LegacyDataTreeGuardTest {

    @TempDir Path repoRoot;

    @Test
    void detectsLegacyAgentsPlansDirectory() throws IOException {
        Files.createDirectories(repoRoot.resolve(".agents").resolve("plans"));
        assertTrue(LegacyDataTreeGuard.isLegacyDataTree(repoRoot));
    }

    @Test
    void cleanRepoIsNotLegacy() {
        assertFalse(LegacyDataTreeGuard.isLegacyDataTree(repoRoot));
    }

    @Test
    void bareAgentsDirWithoutPlansIsNotLegacy() throws IOException {
        // .agents/ as the emerging agent-CONFIG convention (e.g. skills/, mcp) — not
        // a shipsmooth data tree. Must not be detected.
        Files.createDirectories(repoRoot.resolve(".agents").resolve("skills"));
        assertFalse(LegacyDataTreeGuard.isLegacyDataTree(repoRoot));
    }

    @Test
    void agentsPlansAsAFileIsNotLegacy() throws IOException {
        Files.createDirectories(repoRoot.resolve(".agents"));
        Files.writeString(repoRoot.resolve(".agents").resolve("plans"), "not a dir");
        assertFalse(LegacyDataTreeGuard.isLegacyDataTree(repoRoot));
    }
}
