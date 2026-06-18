package io.bitken.ss.cli;

import io.bitken.ss.Build;
import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class ShipsmoothTest {

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
            .servicesModule(new ServicesModule(Paths.get("."), ExperimentalModeParser.fromArgs(args)))
            .build();
        return new Shipsmooth(app, args).execute();
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
}
