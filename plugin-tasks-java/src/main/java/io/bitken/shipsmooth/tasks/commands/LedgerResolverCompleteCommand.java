package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

public class LedgerResolverCompleteCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final LedgerService ledgerService;

    @Inject
    public LedgerResolverCompleteCommand(LedgerService ledgerService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.ledgerService = ledgerService;
        this.spec.name("ledger-resolver-complete");
        this.spec.usageMessage().description("Signal that the Lead Agent's resolver subagent has finished (unblocks integrate).");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--repo").description("Repo root (default: current directory)").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        var plan = (int) pr.matchedOption("plan").getValue();
        var task = (int) pr.matchedOption("task").getValue();
        var repo = (String) pr.matchedOptionValue("repo", null);

        var ledger = repo != null ? new LedgerService(Paths.get(repo)) : this.ledgerService;
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
