package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.di.AppComponents;
import io.bitken.shipsmooth.tasks.di.DaggerAppComponents;
import io.bitken.shipsmooth.tasks.di.ServicesModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TasksCliTest {

    private static final List<String> EXPERIMENTAL_NAMES = List.of(
        "claim", "worker-init", "worker-finish", "worker-cleanup", "worker-base",
        "integrate", "ledger-watch", "ledger-resolver-complete",
        "ledger-record-commit", "ledger-record-patch-integrated"
    );

    private AppComponents app;
    private TasksCli cli;
    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    public void setUp() {
        app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get(".")))
            .build();
        cli = new TasksCli(app);
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

    @Test
    public void integrateRefusedWithoutFlag() {
        int exit = cli.execute("integrate", "--plan", "1");
        assertEquals(2, exit, "integrate must be refused without --enable-experimental");
        // picocli surfaces the refusal via its standard error path; the
        // important contract is that the subcommand is not registered, which
        // produces a usage banner mentioning the parent command's commands.
        assertFalse(err().contains("integrate"),
            "stderr usage banner should not mention 'integrate' when gate is off; got: " + err());
    }

    @Test
    public void integrateRunsWithFlag() {
        int exit = cli.execute("--enable-experimental", "integrate", "--help");
        assertEquals(0, exit, "integrate --help with flag should exit 0");
        assertTrue(out().contains("integrate"), "stdout should contain integrate's usage; got: " + out());
    }

    @Test
    public void helpWithoutFlagHidesExperimentalSubcommands() {
        int exit = cli.execute("--help");
        assertEquals(0, exit);
        String stdout = out();
        for (String name : EXPERIMENTAL_NAMES) {
            assertFalse(containsSubcommandLine(stdout, name),
                "default --help should not list experimental subcommand '" + name + "'");
        }
    }

    @Test
    public void helpWithFlagListsExperimentalSubcommands() {
        int exit = cli.execute("--enable-experimental", "--help");
        assertEquals(0, exit);
        String stdout = out();
        for (String name : EXPERIMENTAL_NAMES) {
            assertTrue(containsSubcommandLine(stdout, name),
                "--enable-experimental --help should list '" + name + "'; got:\n" + stdout);
        }
    }

    @Test
    public void nonExperimentalCommandRecognizedWithoutFlag() {
        // 'show' is non-experimental: it must be parsed as a subcommand even
        // without --enable-experimental. The command may complain about a
        // missing --plan, but stderr should mention the show command, not
        // surface an "unknown subcommand" usage banner.
        cli.execute("show");
        String stderr = err();
        // Either show ran (and complained about --plan) or the test would
        // have shown "Missing required subcommand" at top level.
        assertFalse(stderr.contains("Missing required subcommand"),
            "non-experimental 'show' should be parsed as a subcommand; got: " + stderr);
    }

    @Test
    public void flagAfterSubcommandIsRejected() {
        // Top-level flag must precede subcommand; placed after, the subcommand
        // itself is unmatched without the flag taking effect.
        int exit = cli.execute("integrate", "--enable-experimental");
        assertEquals(2, exit, "flag after subcommand should be rejected (subcommand still unknown)");
    }

    @Test
    public void experimentalFlagVisibilityMatchesBuild() {
        int exit = cli.execute("--help");
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

    private static boolean containsSubcommandLine(String helpOutput, String subcommand) {
        for (String line : helpOutput.split("\\R")) {
            if (line.trim().startsWith(subcommand + " ") || line.trim().equals(subcommand)) return true;
        }
        return false;
    }
}
