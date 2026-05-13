package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;
import java.util.Map;

public class LedgerResolverCompleteCommand {

    public LedgerResolverCompleteCommand() {
    }

    public int execute(int plan, int task, String repo) throws Exception {
        LedgerService ledger = new LedgerService(repo != null ? Paths.get(repo) : Paths.get("."));
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(
            EventType.RESOLVER_COMPLETE,
            String.valueOf(task),
            null,
            null,
            Map.of("task_id", String.valueOf(task))));
        System.out.println("Resolver complete recorded for task " + task);
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Signal that the Lead Agent's resolver subagent has finished (unblocks integrate).");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--task")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--repo")
            .description("Repo root (default: current directory)")
            .type(String.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String repo = pr.matchedOptionValue("repo", null);
        try {
            return new LedgerResolverCompleteCommand().execute(plan, task, repo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}