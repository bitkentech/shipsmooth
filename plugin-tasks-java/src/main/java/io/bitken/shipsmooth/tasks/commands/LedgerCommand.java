package io.bitken.shipsmooth.tasks.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class LedgerCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public LedgerCommand() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("ledger");
        spec.usageMessage().description("Inspect the append-only task ledger.");

        HasSpec[] subcommands = { new ListCmd(), new VerifyCmd(), new ReadCmd() };
        for (HasSpec sub : subcommands) {
            spec.addSubcommand(sub.getSpec().name(), sub.getSpec());
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

        public ListCmd() {
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
            boolean count = pr.hasMatchedOption("count");

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

    public static class VerifyCmd implements Callable<Integer>, HasSpec {

        private final CommandSpec spec;

        public VerifyCmd() {
            spec = CommandSpec.wrapWithoutInspection(this);
            spec.name("verify");
            spec.usageMessage().description("Verify ledger integrity by reconstructing the full timeline.");
        }

        public CommandSpec getSpec() {
            return spec;
        }

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

    public static class ReadCmd implements Callable<Integer>, HasSpec {

        private final CommandSpec spec;

        public ReadCmd() {
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
            String sha = pr.matchedPositional(0).getValue();

            LedgerService ledger = new LedgerService(Paths.get("."));
            Event ev;
            try {
                ev = ledger.readEvent(sha);
            } catch (java.io.IOException e) {
                System.err.printf("ERROR: '%s' not found in ledger object store (.agents/objects/).%n", sha);
                System.err.println("       Git commit SHAs live in .git/ — use 'worker-base' to resolve a task's recorded commit SHA.");
                return 1;
            }
            ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(ev));
            return 0;
        }
    }
}