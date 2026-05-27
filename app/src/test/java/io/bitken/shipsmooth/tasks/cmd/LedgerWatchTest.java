package io.bitken.shipsmooth.tasks.cmd;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LedgerWatchTest {

    @TempDir
    Path tempDir;

    @Test
    public void watchBlocksUntilResolverRequestedThenPrintsPayload() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        // Write non-matching events before the watcher starts
        ledger.record(Event.forTask(EventType.COMMENT_ADDED, "1", null, "not a resolver event", null));
        ledger.record(Event.forTask(EventType.STATUS_UPDATED, "1", null, "still not resolver", null));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream savedOut = System.out;
        System.setOut(new PrintStream(out));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> future = exec.submit(() ->
                    new CommandLine(new LedgerWatch().getSpec())
                            .execute("--plan", "99", "--repo", tempDir.toString()));

            // Give watcher time to start and scan existing non-matching entries
            Thread.sleep(300);
            assertFalse(future.isDone(), "ledger-watch should still be blocking");

            // Another non-matching event while watcher is live
            ledger.record(Event.forTask(EventType.COMMIT_RECORDED, "1", null, "commit", null));
            Thread.sleep(200);
            assertFalse(future.isDone(), "ledger-watch should still be blocking after non-matching event");

            // Append RESOLVER_REQUESTED — should unblock the watcher
            ledger.record(Event.forTask(EventType.RESOLVER_REQUESTED, "1", null,
                    "resolve this conflict please", Map.of("worktree", "/tmp/wt", "attempt", "1")));

            Integer exitCode = future.get(5, TimeUnit.SECONDS);
            assertEquals(0, exitCode, "ledger-watch should exit 0 on RESOLVER_REQUESTED");

            String output = out.toString();
            assertTrue(output.contains("RESOLVER_REQUESTED"), "Output should contain event type");
            assertTrue(output.contains("resolve this conflict please"), "Output should contain payload");
        } finally {
            System.setOut(savedOut);
            exec.shutdownNow();
        }
    }

    @Test
    public void watchSkipsResolverRequestedThatAlreadyHasMatchingComplete() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        // Simulate a completed resolver cycle already in the ledger
        ledger.record(Event.forTask(EventType.RESOLVER_REQUESTED, "1", null,
                "old request", Map.of("worktree", "/tmp/wt", "attempt", "1")));
        Thread.sleep(10); // ensure COMPLETE timestamp > REQUESTED timestamp
        ledger.record(Event.forTask(EventType.RESOLVER_COMPLETE, "1", null, null,
                Map.of("task_id", "1")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream savedOut = System.out;
        System.setOut(new PrintStream(out));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> future = exec.submit(() ->
                    new CommandLine(new LedgerWatch().getSpec())
                            .execute("--plan", "99", "--repo", tempDir.toString()));

            // Should not fire on the already-resolved request
            Thread.sleep(400);
            assertFalse(future.isDone(), "ledger-watch should skip already-resolved RESOLVER_REQUESTED");

            // New unresolved request — should fire
            ledger.record(Event.forTask(EventType.RESOLVER_REQUESTED, "1", null,
                    "new request", Map.of("worktree", "/tmp/wt", "attempt", "2")));

            Integer exitCode = future.get(5, TimeUnit.SECONDS);
            assertEquals(0, exitCode);
            assertTrue(out.toString().contains("new request"), "Should output the new unresolved request");
        } finally {
            System.setOut(savedOut);
            exec.shutdownNow();
        }
    }

    @Test
    public void watchAfterSkipsStaleTerminalEvent() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        // Stale INTEGRATION_COMPLETE from a prior run at index 0
        ledger.record(Event.system(EventType.INTEGRATION_COMPLETE, null, "prior run finished", null));

        // Snapshot count (1) — this is what the Lead Agent passes as --after
        int snapshot = 1;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream savedOut = System.out;
        System.setOut(new PrintStream(out));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> future = exec.submit(() ->
                    new CommandLine(new LedgerWatch().getSpec())
                            .execute("--plan", "99", "--repo", tempDir.toString(),
                                    "--after", String.valueOf(snapshot)));

            // Should not fire on the stale INTEGRATION_COMPLETE
            Thread.sleep(400);
            assertFalse(future.isDone(), "ledger-watch should ignore stale events before --after index");

            // New RESOLVER_REQUESTED at index >= snapshot — should fire
            ledger.record(Event.forTask(EventType.RESOLVER_REQUESTED, "2", null,
                    "resolve new conflict", Map.of("worktree", "/tmp/wt2", "attempt", "1")));

            Integer exitCode = future.get(5, TimeUnit.SECONDS);
            assertEquals(0, exitCode);
            assertTrue(out.toString().contains("resolve new conflict"));
        } finally {
            System.setOut(savedOut);
            exec.shutdownNow();
        }
    }

    @Test
    public void watchExitsOneOnTimeout() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream savedErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            int exit = new CommandLine(new LedgerWatch().getSpec())
                    .execute("--plan", "99", "--repo", tempDir.toString(), "--timeout-seconds", "1");
            assertEquals(1, exit, "Should exit 1 on timeout");
            assertTrue(err.toString().contains("timed out"), "Should print timeout message");
        } finally {
            System.setErr(savedErr);
        }
    }
}