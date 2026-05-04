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
            int exit = new CommandLine(new LedgerCommand()).execute("list");
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
            int exit = new CommandLine(new LedgerCommand()).execute("list", "--task", "42");
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains("COMMENT_ADDED"));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void ledgerVerifyExitsZero() {
        int exit = new CommandLine(new LedgerCommand()).execute("verify");
        assertEquals(0, exit);
    }

    @Test
    public void ledgerReadPrintsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new LedgerCommand()).execute("read", recordedSha);
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains("COMMENT_ADDED"));
            assertTrue(output.contains("42"));
        } finally {
            System.setOut(original);
        }
    }
}
