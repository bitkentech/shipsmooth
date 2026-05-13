package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

public class LedgerResolverCompleteCommand implements Callable<Integer> {

    private CommandSpec spec;

    public CommandSpec getSpec() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Signal that the Lead Agent's resolver subagent has finished (unblocks integrate).");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--repo").description("Repo root (default: current directory)").type(String.class).build());
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String repo = pr.matchedOptionValue("repo", null);

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
}