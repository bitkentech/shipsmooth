package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11 (Provider leaf wiring): the command tree must be <em>comprehensive</em> even when
 * the store is unsettled — {@code plan} and {@code task} appear in {@code --help} on a clean
 * first run, because the leaves now hold {@code Provider}s and touch the state root only
 * inside {@code call()}. A state-dependent command still gates (the state touch happens at
 * dispatch, where the resolve-gate intercepts it), but now via the post-parse path rather
 * than a parse-time "unknown command" failure.
 */
public class UnsettledTreeIntegrationTest {

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

    private String combined() {
        System.out.flush();
        System.err.flush();
        return out.toString() + err.toString();
    }

    private static AppComponents unsettledApp() {
        return DaggerAppComponents.builder()
                .servicesModule(ServicesModule.unsettled(Paths.get("."), new ExperimentalMode(false)))
                .build();
    }

    private static DataStoreResolution needsDecision() {
        return new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.EXTERNAL, Path.of("/ext"), true)));
    }

    private int run(DataStoreResolution resolution, String... args) {
        return new Shipsmooth(unsettledApp(), args,
                Paths.get(".").toAbsolutePath().normalize(), Optional.empty(), resolution).execute();
    }

    @Test
    void rootHelp_whenUnsettled_listsPlanAndTask() {
        int exit = run(needsDecision(), "--help");

        assertEquals(0, exit, "root --help should exit 0; output: " + combined());
        String text = combined();
        assertTrue(text.contains("plan"), "unsettled root --help must list `plan`: " + text);
        assertTrue(text.contains("task"), "unsettled root --help must list `task`: " + text);
    }

    @Test
    void planLeafHelp_whenUnsettled_printsUsageExit0() {
        int exit = run(needsDecision(), "plan", "resume", "--help");

        assertEquals(0, exit, "`plan resume --help` should exit 0 even unsettled; output: " + combined());
        assertTrue(combined().contains("Usage:"),
                "`plan resume --help` should print usage; output: " + combined());
    }

    @Test
    void taskLeafHelp_whenUnsettled_printsUsageExit0() {
        int exit = run(needsDecision(), "task", "add", "--help");

        assertEquals(0, exit, "`task add --help` should exit 0 even unsettled; output: " + combined());
        assertTrue(combined().contains("Usage:"),
                "`task add --help` should print usage; output: " + combined());
    }

    @Test
    void stateCommand_whenUnsettled_stillGatesExit10() {
        int exit = run(needsDecision(), "plan", "resume", "--plan", "1");

        assertEquals(Shipsmooth.EXIT_NEEDS_DECISION, exit,
                "a real state-dependent command must still gate; output: " + combined());
        assertTrue(combined().contains("\"status\":\"needs-decision\""), combined());
    }
}
