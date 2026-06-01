package io.bitken.ss.cli.ledger;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventLedger;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.util.concurrent.Callable;

public class Ledger implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final EventLedger ledgerService;

    @Inject
    public Ledger(EventLedger ledgerService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.ledgerService = ledgerService;
        this.spec.name("ledger");
        this.spec.usageMessage().description("Inspect the append-only task ledger.");

        HasSpec[] subcommands = { new ListCmd(ledgerService), new VerifyCmd(ledgerService), new ReadCmd(ledgerService) };
        for (HasSpec sub : subcommands) {
            this.spec.addSubcommand(sub.getSpec().name(), sub.getSpec());
        }
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        System.err.println("Usage: ledger <list|verify|read>");
        return 0;
    }

    public static class ListCmd implements Callable<Integer>, HasSpec {

        private final CommandSpec spec;
        private final EventLedger ledgerService;

        public ListCmd(EventLedger ledgerService) {
            this.ledgerService = ledgerService;
            spec = CommandSpec.wrapWithoutInspection(this);
            spec.name("list");
            spec.usageMessage().description("List ledger entries.");
            spec.addOption(OptionSpec.builder("--task").description("Filter by task ID.").type(String.class).build());
            spec.addOption(OptionSpec.builder("--type").description("Filter by event type.").type(String.class).build());
            spec.addOption(OptionSpec.builder("--count").description("Print total event count as a plain integer and exit.").type(boolean.class).build());
        }

        public CommandSpec getSpec() {
            return spec;
        }

        @Override
        public Integer call() throws Exception {
            var pr = spec.commandLine().getParseResult();
            String taskId = pr.matchedOptionValue("task", null);
            String type = pr.matchedOptionValue("type", null);
            var count = pr.hasMatchedOption("count");

            var hashes = ledgerService.readHashes();
            if (count) {
                System.out.println(hashes.size());
                return 0;
            }
            for (int i = 0; i < hashes.size(); i++) {
                var hash = hashes.get(i);
                var ev = ledgerService.readEvent(hash);
                if (matches(ev, taskId, type)) {
                    printRow(i, hash, ev);
                }
            }
            return 0;
        }

        private boolean matches(io.bitken.ss.ledger.Event ev, String taskId, String type) {
            if (taskId != null && !taskId.equals(ev.taskId())) return false;
            if (type != null && !type.equalsIgnoreCase(ev.eventType().name())) return false;
            return true;
        }

        private void printRow(int i, String hash, io.bitken.ss.ledger.Event ev) {
            var taskLabel = ev.taskId() != null ? ev.taskId() : "<system>";
            var summary = summarize(ev);
            System.out.printf("[%03d] %s %s | %s | %s | %s%n",
                i, hash.substring(0, 8), ev.eventType(), taskLabel, ev.timestamp(), summary);
        }

        private String summarize(io.bitken.ss.ledger.Event ev) {
            if (ev.payload() != null) return ev.payload().lines().findFirst().orElse("");
            if (ev.baseCommitSha() != null) return "commit=" + ev.baseCommitSha().substring(0, Math.min(8, ev.baseCommitSha().length()));
            return "";
        }
    }

    public static class VerifyCmd implements Callable<Integer>, HasSpec {

        private final CommandSpec spec;
        private final EventLedger ledgerService;

        public VerifyCmd(EventLedger ledgerService) {
            this.ledgerService = ledgerService;
            spec = CommandSpec.wrapWithoutInspection(this);
            spec.name("verify");
            spec.usageMessage().description("Verify ledger integrity by reconstructing the full timeline.");
        }

        public CommandSpec getSpec() {
            return spec;
        }

        @Override
        public Integer call() throws Exception {
            try {
                var timeline = ledgerService.verifyLedger();
                System.out.println("OK: " + timeline.size() + " entries verified.");
                return 0;
            } catch (Exception e) {
                System.err.println("FAIL: " + e.getMessage());
                return 1;
            }
        }
    }

    public static class ReadCmd implements Callable<Integer>, HasSpec {

        private final CommandSpec spec;
        private final EventLedger ledgerService;

        public ReadCmd(EventLedger ledgerService) {
            this.ledgerService = ledgerService;
            spec = CommandSpec.wrapWithoutInspection(this);
            spec.name("read");
            spec.usageMessage().description("Print the JSON event blob for a given SHA.");
            spec.addPositional(PositionalParamSpec.builder()
                .index("0")
                .description("SHA-1 of the event to read.")
                .required(true)
                .build());
        }

        public CommandSpec getSpec() {
            return spec;
        }

        @Override
        public Integer call() throws Exception {
            var pr = spec.commandLine().getParseResult();
            var sha = pr.matchedPositional(0).getValue().toString();

            Event ev;
            try {
                ev = ledgerService.readEvent(sha);
            } catch (java.io.IOException e) {
                System.err.printf("ERROR: '%s' not found in ledger object store (.agents/objects/).%n", sha);
                System.err.println("       Git commit SHAs live in .git/ — use 'worker-base' to resolve a task's recorded commit SHA.");
                return 1;
            }
            System.out.println(ledgerService.renderEventJson(ev));
            return 0;
        }
    }
}
