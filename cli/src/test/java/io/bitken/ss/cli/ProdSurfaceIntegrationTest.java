package io.bitken.ss.cli;

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

/**
 * Plan-75 end-to-end guard: when experimental mode is OFF, the CLI must not
 * offer any experimental subcommand in its {@code --help} listing.
 *
 * <p>This drives the full command tree the way {@code main()} does, but pins
 * {@link ExperimentalMode} to {@code false} explicitly so the assertion is
 * independent of the ambient {@code Build.EXPERIMENTAL_BUILD} of the test JVM.
 * The existing {@link ShipsmoothTest#experimentalFlagVisibilityMatchesBuild}
 * branches on that build constant and therefore only ever exercises the dev arm
 * in a dev build — the exact blind spot that let the 0.3.17 prod leak ship.
 *
 * <p>Crucially the experimental-name set here includes {@code ledger}, which
 * today is registered unconditionally (it does not implement {@code FeatureFlags}).
 * This test is therefore RED until plan-75 Task 3 gates the ledger group.
 */
public class ProdSurfaceIntegrationTest {

    /**
     * Every top-level command that a prod (non-experimental) CLI must hide.
     * {@code ledger} is included deliberately: plan-75 makes the whole group
     * experimental.
     */
    private static final List<String> EXPERIMENTAL_NAMES = List.of(
        "claim", "worker", "integrate", "ledger"
    );

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

    /** One-shot CLI pinned to non-experimental mode, mirroring main(). */
    private int runProd(String... args) {
        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get("."), new ExperimentalMode(false)))
            .build();
        return new Shipsmooth(app, args).execute();
    }

    @Test
    public void prodHelpListsNoExperimentalSubcommand() {
        int exit = runProd("--help");
        assertEquals(0, exit);
        String stdout = outBuf.toString();
        for (String name : EXPERIMENTAL_NAMES) {
            assertFalse(containsSubcommandLine(stdout, name),
                "prod (--enable-experimental OFF) --help must not list experimental "
                    + "subcommand '" + name + "'; got:\n" + stdout);
        }
    }

    /**
     * plan-75: the root usage description is prose that shows even in prod --help, so it
     * must not advertise an experimental command (it once read "...tasks, subagents and
     * ledger...", naming the now-experimental ledger group).
     */
    @Test
    public void prodHelpDescriptionNamesNoExperimentalCommand() {
        int exit = runProd("--help");
        assertEquals(0, exit);
        String description = firstDescriptionLine(outBuf.toString());
        for (String name : EXPERIMENTAL_NAMES) {
            assertFalse(description.toLowerCase().contains(name),
                "prod --help description must not name experimental command '" + name
                    + "'; got: " + description);
        }
    }

    /** The usage description line (the prose between the Usage: line and the options). */
    private static String firstDescriptionLine(String helpOutput) {
        String[] lines = helpOutput.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("Usage:") && i + 1 < lines.length) return lines[i + 1];
        }
        return "";
    }

    private static boolean containsSubcommandLine(String helpOutput, String subcommand) {
        for (String line : helpOutput.split("\\R")) {
            if (line.trim().startsWith(subcommand + " ") || line.trim().equals(subcommand)) return true;
        }
        return false;
    }
}
