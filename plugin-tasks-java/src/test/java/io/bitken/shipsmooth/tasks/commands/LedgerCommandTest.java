package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class LedgerCommandTest {

    private LedgerService ledger;
    private String recordedSha;

    @BeforeEach
    public void setUp() throws Exception {
        ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        recordedSha = ledger.record(Event.forTask(EventType.COMMENT_ADDED, "42", null, "LedgerCommandTest probe", null));
    }

    @Test
    public void ledgerListShowsEntries() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("list");
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains(recordedSha.substring(0, 8)), "Expected sha8 in list output");
            assertTrue(output.contains("COMMENT_ADDED"), "Expected event type in list output");
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void ledgerListFilterByTask() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("list", "--task", "42");
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains("COMMENT_ADDED"));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void ledgerVerifyExitsZero() {
        int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("verify");
        assertEquals(0, exit);
    }

    @Test
    public void ledgerReadPrintsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("read", recordedSha);
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains("COMMENT_ADDED"));
            assertTrue(output.contains("42"));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void ledgerReadExitsOneForUnknownSha() {
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("read", "73fbd39585a74a3e70e6620699b48d2ea31dc5ed");
            assertEquals(1, exit);
            String errOutput = err.toString();
            assertTrue(errOutput.contains("not found in ledger object store"), "Expected helpful error message");
            assertTrue(errOutput.contains("worker-base"), "Expected hint about worker-base command");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    public void ledgerListCountPrintsInteger() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("list", "--count");
            assertEquals(0, exit);
            String output = out.toString().trim();
            int count = Integer.parseInt(output);
            assertTrue(count >= 1, "Count should be at least 1 (setUp recorded an event)");
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void ledgerListCountIgnoresFilters() {
        // --count with --task filter still prints just the total count, not filtered count
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("list", "--count", "--task", "42");
            assertEquals(0, exit);
            String output = out.toString().trim();
            // Must be parseable as integer and print nothing else
            assertDoesNotThrow(() -> Integer.parseInt(output));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void ledgerReadAcceptsShaPrefix() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand(ledger).getSpec()).execute("read", recordedSha.substring(0, 8));
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains("COMMENT_ADDED"));
            assertTrue(output.contains("42"));
        } finally {
            System.setOut(original);
        }
    }
}
