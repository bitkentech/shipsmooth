package io.bitken.shipsmooth.tasks.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class LedgerCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final LedgerService ledgerService;

    @Inject
    public LedgerCommand(LedgerService ledgerService) {
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
        private final LedgerService ledgerService;

        public ListCmd(LedgerService ledgerService) {
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
                if (taskId != null && !taskId.equals(ev.taskId())) continue;
                if (type != null && !type.equalsIgnoreCase(ev.eventType().name())) continue;
                var taskLabel = ev.taskId() != null ? ev.taskId() : "<system>";
                var summary = ev.payload() != null
                    ? ev.payload().lines().findFirst().orElse("")
                    : (ev.baseCommitSha() != null ? "commit=" + ev.baseCommitSha().substring(0, Math.min(8, ev.baseCommitSha().length())) : "");
                System.out.printf("[%03d] %s %s | %s | %s | %s%n",
                    i, hash.substring(0, 8), ev.eventType(), taskLabel, ev.timestamp(), summary);
            }
            return 0;
        }
    }

    public static class VerifyCmd implements Callable<Integer>, HasSpec {

        private final CommandSpec spec;
        private final LedgerService ledgerService;

        public VerifyCmd(LedgerService ledgerService) {
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
        private final LedgerService ledgerService;

        public ReadCmd(LedgerService ledgerService) {
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
            var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(ev));
            return 0;
        }
    }
}
