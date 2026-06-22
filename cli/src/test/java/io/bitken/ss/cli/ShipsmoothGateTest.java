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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The startup resolve-gate: when the store is unsettled, a state-dependent command must not
 * run — it emits the resolution as JSON and exits with a distinct code.
 */
public class ShipsmoothGateTest {

    private ByteArrayOutputStream outBuf;
    private PrintStream originalOut;

    @BeforeEach
    public void setUp() {
        outBuf = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outBuf));
    }

    @AfterEach
    public void tearDown() {
        System.setOut(originalOut);
    }

    private String out() {
        System.out.flush();
        return outBuf.toString();
    }

    private static AppComponents unsettledApp() {
        return DaggerAppComponents.builder()
                .servicesModule(ServicesModule.unsettled(Paths.get("."), new ExperimentalMode(false)))
                .build();
    }

    private static int run(DataStoreResolution resolution, String... args) {
        return new Shipsmooth(unsettledApp(), args,
                Paths.get(".").toAbsolutePath().normalize(), Optional.empty(), resolution).execute();
    }

    @Test
    public void needsDecision_gatesStateCommand_emitsJsonExit10() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.EXTERNAL, Path.of("/ext"), true)));

        int code = run(needs, "plan", "resume", "--plan", "1");

        assertEquals(Shipsmooth.EXIT_NEEDS_DECISION, code);
        assertTrue(out().contains("\"status\":\"needs-decision\""), out());
    }

    @Test
    public void unresolvable_gatesStateCommand_emitsJsonExit11() {
        var bad = DataStoreResolution.Unresolvable.of(
                DataStoreResolution.UnresolvableReason.LEGACY_AGENTS_TREE);

        int code = run(bad, "plan", "resume", "--plan", "1");

        assertEquals(Shipsmooth.EXIT_UNRESOLVABLE, code);
        assertTrue(out().contains("\"status\":\"unresolvable\""), out());
    }

    @Test
    public void unknownCommand_whenSettled_fallsToUsageError() {
        var settled = new DataStoreResolution.Settled(
                new io.bitken.ss.cli.conf.ds.ProjectDataStore.InRepo(
                        Paths.get(".").toAbsolutePath().normalize()));
        AppComponents settledApp = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get("."), new ExperimentalMode(false)))
                .build();
        int code = new Shipsmooth(settledApp, new String[]{"no-such-command"},
                Paths.get(".").toAbsolutePath().normalize(), Optional.empty(), settled).execute();
        assertNotEquals(0, code, "an unknown command in settled mode is a usage error");
    }

    @Test
    public void storeCommand_isNotGated_whenUnsettled() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.EXTERNAL, Path.of("/ext"), true)));

        // `store` with no leaf prints its own usage (exit 0/2 from picocli), NOT the gate JSON.
        int code = run(needs, "store");
        assertFalse(out().contains("\"status\":\"needs-decision\""),
                "store must bypass the resolve-gate");
    }
}
