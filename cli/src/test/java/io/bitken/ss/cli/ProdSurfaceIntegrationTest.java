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
 * End-to-end guard: when experimental mode is OFF, the CLI must not offer any of
 * the removed parallel-execution subcommands in its {@code --help} listing.
 *
 * <p>This drives the full command tree the way {@code main()} does, pinning
 * {@link ExperimentalMode} to {@code false} explicitly. Plan-82 deleted the
 * {@code claim} / {@code worker} / {@code integrate} / {@code ledger} subsystem
 * entirely; this test stays as a regression guard ensuring none of those names
 * ever reappears in the prod surface (listing or description prose).
 */
public class ProdSurfaceIntegrationTest {

    /** Removed subcommands that must never surface in prod --help (plan-82). */
    private static final List<String> REMOVED_NAMES = List.of(
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
        for (String name : REMOVED_NAMES) {
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
        for (String name : REMOVED_NAMES) {
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
