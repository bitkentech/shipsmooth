package io.bitken.ss;

import io.bitken.ss.cli.Shipsmooth;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShipsmoothTest {

    // Top-level experimental commands. After the noun-group regrouping the
    // worker-* leaves live under the experimental `worker` group and the
    // ledger record-*/watch leaves under the (non-experimental) `ledger` group,
    // so only these names are experimental *at the top level*.
    private static final List<String> EXPERIMENTAL_NAMES = List.of(
        "claim", "worker", "integrate"
    );

    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    public void setUp() {
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
    }

    @AfterEach
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String out() { return outBuf.toString(); }
    private String err() { return errBuf.toString(); }

    /** Build a one-shot CLI bound to these args, mirroring main(), and run it. */
    private int run(String... args) {
        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get("."), ExperimentalMode.fromArgs(args)))
            .build();
        return new Shipsmooth(app, args).execute();
    }

    @Test
    public void integrateRefusedWithoutFlag() {
        int exit = run("integrate", "--plan", "1");
        assertEquals(2, exit, "integrate must be refused without --enable-experimental");
        // The contract is that 'integrate' is not a registered subcommand. picocli
        // may echo the unmatched token in its error, but it must not present
        // 'integrate' as an available command in the Commands: usage listing.
        assertFalse(containsSubcommandLine(err(), "integrate"),
            "'integrate' must not be offered as a valid subcommand when gate is off; got: " + err());
    }

    @Test
    public void integrateRunsWithFlag() {
        int exit = run("--enable-experimental", "integrate", "--help");
        assertEquals(0, exit, "integrate --help with flag should exit 0");
        assertTrue(out().contains("integrate"), "stdout should contain integrate's usage; got: " + out());
    }

    @Test
    public void helpWithoutFlagHidesExperimentalSubcommands() {
        int exit = run("--help");
        assertEquals(0, exit);
        String stdout = out();
        for (String name : EXPERIMENTAL_NAMES) {
            assertFalse(containsSubcommandLine(stdout, name),
                "default --help should not list experimental subcommand '" + name + "'");
        }
    }

    @Test
    public void helpWithFlagListsExperimentalSubcommands() {
        int exit = run("--enable-experimental", "--help");
        assertEquals(0, exit);
        String stdout = out();
        for (String name : EXPERIMENTAL_NAMES) {
            assertTrue(containsSubcommandLine(stdout, name),
                "--enable-experimental --help should list '" + name + "'; got:\n" + stdout);
        }
    }

    @Test
    public void nonExperimentalCommandRecognizedWithoutFlag() {
        // 'plan show' is non-experimental: it must be parsed as a group
        // subcommand even without --enable-experimental. The command may
        // complain about a missing --plan, but stderr should not surface an
        // "unknown subcommand" usage banner for the plan group.
        run("plan", "show");
        String stderr = err();
        // Either 'plan show' ran (and complained about --plan) or the test
        // would have shown "Missing required subcommand" at top level.
        assertFalse(stderr.contains("Missing required subcommand"),
            "non-experimental 'plan show' should be parsed as a subcommand; got: " + stderr);
    }

    @Test
    public void flagAfterSubcommandIsRejected() {
        // Top-level flag must precede subcommand; placed after, the subcommand
        // itself is unmatched without the flag taking effect.
        int exit = run("integrate", "--enable-experimental");
        assertEquals(2, exit, "flag after subcommand should be rejected (subcommand still unknown)");
    }

    @Test
    public void experimentalFlagVisibilityMatchesBuild() {
        int exit = run("--help");
        assertEquals(0, exit);
        String stdout = out();
        if (Build.EXPERIMENTAL_BUILD) {
            assertTrue(stdout.contains("--enable-experimental"),
                "dev build: --help should mention --enable-experimental; got:\n" + stdout);
        } else {
            assertFalse(stdout.contains("--enable-experimental"),
                "prod build: --help must not mention --enable-experimental; got:\n" + stdout);
        }
    }

    @Test
    public void versionFlagPrintsProjectVersion() {
        int exit = run("--version");
        assertEquals(0, exit);
        assertTrue(out().contains(Build.VERSION),
            "--version should print Build.VERSION (" + Build.VERSION + "); got: " + out());
    }

    private static boolean containsSubcommandLine(String helpOutput, String subcommand) {
        for (String line : helpOutput.split("\\R")) {
            if (line.trim().startsWith(subcommand + " ") || line.trim().equals(subcommand)) return true;
        }
        return false;
    }
}
