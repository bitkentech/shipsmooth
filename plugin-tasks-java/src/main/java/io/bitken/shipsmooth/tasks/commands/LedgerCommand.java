package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;
import java.util.List;

public class LedgerCommand {

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Inspect the append-only task ledger.");
        spec.addSubcommand("list", ListCmd.getSpec());
        spec.addSubcommand("verify", VerifyCmd.getSpec());
        spec.addSubcommand("read", ReadCmd.getSpec());
        return spec;
    }

    public static int run(ParseResult pr) {
        System.err.println("Usage: ledger <list|verify|read>");
        return 0;
    }

    public static class ListCmd {
        public int execute(String taskId, String type, boolean count) throws Exception {
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

        public static CommandSpec getSpec() {
            CommandSpec spec = CommandSpec.create();
            spec.usageMessage().description("List ledger entries.");
            spec.addOption(OptionSpec.builder("--task").description("Filter by task ID.").type(String.class).build());
            spec.addOption(OptionSpec.builder("--type").description("Filter by event type.").type(String.class).build());
            spec.addOption(OptionSpec.builder("--count").description("Print total event count as a plain integer and exit.").type(boolean.class).build());
            return spec;
        }

        public static int run(ParseResult pr) {
            String taskId = pr.matchedOptionValue("task", null);
            String type = pr.matchedOptionValue("type", null);
            boolean count = pr.hasMatchedOption("count");
            try {
                return new ListCmd().execute(taskId, type, count);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class VerifyCmd {
        public int execute() throws Exception {
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

        public static CommandSpec getSpec() {
            CommandSpec spec = CommandSpec.create();
            spec.usageMessage().description("Verify ledger integrity by reconstructing the full timeline.");
            return spec;
        }

        public static int run(ParseResult pr) {
            try {
                return new VerifyCmd().execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class ReadCmd {
        public int execute(String sha) throws Exception {
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

        public static CommandSpec getSpec() {
            CommandSpec spec = CommandSpec.create();
            spec.usageMessage().description("Print the JSON event blob for a given SHA.");
            spec.addPositional(PositionalParamSpec.builder()
                .index("0")
                .description("SHA-1 of the event to read.")
                .required(true)
                .build());
            return spec;
        }

        public static int run(ParseResult pr) {
            String sha = pr.matchedPositional(0).getValue();
            try {
                return new ReadCmd().execute(sha);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}