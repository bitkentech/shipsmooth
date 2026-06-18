package io.bitken.ss.cli;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 preamble (plan-83): walks the whole command tree asserting that
 * {@code --help} prints usage and exits 0 at every level — root, both noun
 * groups, and every leaf under {@code plan} and {@code task}.
 *
 * <p>Today the 13 leaves regress: their specs are attached without
 * {@code mixinStandardHelpOptions}, so {@code --help} is treated as an unknown
 * arg, required-option validation fires first, and the command exits 2 with a
 * {@code Missing required option} error instead of help. This test is expected
 * to fail (red) until Task 1 fixes the {@code addLeaves} seam.
 */
public class HelpAcrossTreeIntegrationTest {

    private final AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get("."), new ExperimentalMode(true)))
            .build();

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @ParameterizedTest(name = "`{0} --help` exits 0 with usage")
    @CsvSource({
            // root and groups (already working — guards against regression)
            "''",
            "plan",
            "task",
            // plan leaves
            "plan init",
            "plan quick",
            "plan show",
            "plan update",
            "plan preflight",
            "plan tag",
            "plan branch",
            "plan resume",
            // task leaves
            "task add",
            "task comment",
            "task deviation",
            "task status",
            "task set-commit",
    })
    void helpExitsZeroWithUsage(String commandPath) {
        String[] args = argsFor(commandPath);

        int exit = new Shipsmooth(app, args).execute();

        String combined = out.toString() + err.toString();
        assertEquals(0, exit,
                "`" + commandPath + " --help` should exit 0; output was: " + combined);
        assertTrue(combined.contains("Usage:"),
                "`" + commandPath + " --help` should print usage; output was: " + combined);
        assertFalse(combined.contains("Missing required option"),
                "`" + commandPath + " --help` must not demand required options; output was: " + combined);
    }

    private static String[] argsFor(String commandPath) {
        if (commandPath.isEmpty()) {
            return new String[] {"--help"};
        }
        String[] parts = commandPath.split(" ");
        String[] args = new String[parts.length + 1];
        System.arraycopy(parts, 0, args, 0, parts.length);
        args[parts.length] = "--help";
        return args;
    }
}
