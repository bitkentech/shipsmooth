package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan-75 Task 5: the release-time prod guard that would have caught both 0.3.17
 * defects (stale version, leaked experimental flag). The assertion logic is pure —
 * it takes already-captured launcher output / Build.java source and a release
 * version — so it is unit-testable without executing a cross-platform binary.
 */
public class ReleaseGuardTest {

    private static final String GOOD_HELP =
        "Usage: shipsmooth [-hV] [COMMAND]\n  plan   Manage plans\n  task   Manage tasks\n";

    // --- launcher output guard (the executable check, linux-x64) ---

    @Test
    void launcherGuardPassesOnProdOutput() {
        assertDoesNotThrow(() ->
            ReleaseGuard.assertLauncherOutputIsProd("0.3.18\n", GOOD_HELP, "0.3.18"));
    }

    @Test
    void launcherGuardFailsOnVersionMismatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ReleaseGuard.assertLauncherOutputIsProd("0.3.16\n", GOOD_HELP, "0.3.18"));
        assertTrue(ex.getMessage().contains("0.3.16"), ex.getMessage());
        assertTrue(ex.getMessage().contains("0.3.18"), ex.getMessage());
    }

    @Test
    void launcherGuardFailsWhenHelpLeaksExperimentalFlag() {
        String leakyHelp = GOOD_HELP + "      --enable-experimental   Enable experimental subcommands.\n";
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ReleaseGuard.assertLauncherOutputIsProd("0.3.18\n", leakyHelp, "0.3.18"));
        assertTrue(ex.getMessage().contains("enable-experimental"), ex.getMessage());
    }

    @Test
    void launcherGuardFailsWhenHelpLeaksExperimentalSubcommand() {
        String leakyHelp = GOOD_HELP + "  ledger   Inspect and record entries in the ledger.\n";
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ReleaseGuard.assertLauncherOutputIsProd("0.3.18\n", leakyHelp, "0.3.18"));
        assertTrue(ex.getMessage().contains("ledger"), ex.getMessage());
    }

    // --- Build.java source guard (the non-executable check, all platforms) ---

    @Test
    void buildConstantsGuardPassesOnProdSource() {
        String src = """
            package io.bitken.ss;
            public final class Build {
                public static final boolean EXPERIMENTAL_BUILD = false;
                public static final String VERSION = "0.3.18";
                private Build() {}
            }
            """;
        assertDoesNotThrow(() -> ReleaseGuard.assertBuildConstantsAreProd(src, "0.3.18"));
    }

    @Test
    void buildConstantsGuardFailsOnExperimentalTrue() {
        String src = """
            public static final boolean EXPERIMENTAL_BUILD = true;
            public static final String VERSION = "0.3.18";
            """;
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ReleaseGuard.assertBuildConstantsAreProd(src, "0.3.18"));
        assertTrue(ex.getMessage().contains("EXPERIMENTAL_BUILD"), ex.getMessage());
    }

    @Test
    void buildConstantsGuardFailsOnWrongVersion() {
        String src = """
            public static final boolean EXPERIMENTAL_BUILD = false;
            public static final String VERSION = "0.3.16";
            """;
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            ReleaseGuard.assertBuildConstantsAreProd(src, "0.3.18"));
        assertTrue(ex.getMessage().contains("0.3.16"), ex.getMessage());
    }
}
