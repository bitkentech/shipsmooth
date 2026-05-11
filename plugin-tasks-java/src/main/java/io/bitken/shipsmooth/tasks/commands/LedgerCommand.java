package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "ledger", description = "Inspect the append-only task ledger.",
        subcommands = {LedgerCommand.ListCmd.class, LedgerCommand.VerifyCmd.class, LedgerCommand.ReadCmd.class})
public class LedgerCommand implements Runnable {

    @Override
    public void run() {
        System.err.println("Usage: ledger <list|verify|read>");
    }

    @Command(name = "list", description = "List ledger entries.")
    static class ListCmd implements Callable<Integer> {

        @Option(names = "--task", description = "Filter by task ID.")
        private String taskId;

        @Option(names = "--type", description = "Filter by event type.")
        private String type;

        @Option(names = "--count", description = "Print total event count as a plain integer and exit.")
        private boolean count;

        @Override
        public Integer call() throws Exception {
            LedgerService ledger = new LedgerService(Paths.get("."));
            List<String> hashes = ledger.readHashes();
            if (count) {
                System.out.println(hashes.size());
                return 0;
            }
            for (int i = 0; i < hashes.size(); i++) {
                String hash = hashes.get(i);
                Event ev = ledger.readEvent(hash);
                if (taskId != null && !taskId.equals(ev.taskId())) continue;
                if (type != null && !type.equalsIgnoreCase(ev.eventType().name())) continue;
                String taskLabel = ev.taskId() != null ? ev.taskId() : "<system>";
                String summary = ev.payload() != null
                        ? ev.payload().lines().findFirst().orElse("")
                        : (ev.baseCommitSha() != null ? "commit=" + ev.baseCommitSha().substring(0, Math.min(8, ev.baseCommitSha().length())) : "");
                System.out.printf("[%03d] %s %s | %s | %s | %s%n",
                        i, hash.substring(0, 8), ev.eventType(), taskLabel, ev.timestamp(), summary);
            }
            return 0;
        }
    }

    @Command(name = "verify", description = "Verify ledger integrity by reconstructing the full timeline.")
    static class VerifyCmd implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            LedgerService ledger = new LedgerService(Paths.get("."));
            try {
                List<Event> timeline = ledger.verifyLedger();
                System.out.println("OK: " + timeline.size() + " entries verified.");
                return 0;
            } catch (Exception e) {
                System.err.println("FAIL: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "read", description = "Print the JSON event blob for a given SHA.")
    static class ReadCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "SHA-1 of the event to read.")
        private String sha;

        @Override
        public Integer call() throws Exception {
            LedgerService ledger = new LedgerService(Paths.get("."));
            Event ev;
            try {
                ev = ledger.readEvent(sha);
            } catch (java.io.IOException e) {
                System.err.printf("ERROR: '%s' not found in ledger object store (.agents/objects/).%n", sha);
                System.err.println("       Git commit SHAs live in .git/ — use 'worker-base' to resolve a task's recorded commit SHA.");
                return 1;
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(ev));
            return 0;
        }
    }
}
