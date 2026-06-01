package io.bitken.ss.cli.ledger;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.Map;
import java.util.concurrent.Callable;

public class LedgerRecordCommit implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final EventLedger ledger;

    @Inject
    public LedgerRecordCommit(EventLedger ledger) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.ledger = ledger;
        this.spec.name("ledger-record-commit");
        this.spec.usageMessage().description("Write a COMMIT_RECORDED event directly to the ledger (recovery use only).");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--commit").required(true).type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--branch").required(true).description("Must start with agent-work/ to write integration_mode=worktree.").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        var plan = (int) pr.matchedOption("plan").getValue();
        var task = (int) pr.matchedOption("task").getValue();
        var commit = (String) pr.matchedOption("commit").getValue();
        var branch = (String) pr.matchedOption("branch").getValue();

        var integrationMode = branch.startsWith("agent-work/") ? "worktree" : "direct";
        var meta = Map.of(
            "branch", branch,
            "commit_sha", commit,
            "integration_mode", integrationMode
        );
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.COMMIT_RECORDED, String.valueOf(task), commit, commit, meta));
        System.out.println("COMMIT_RECORDED written for task " + task + " (integration_mode=" + integrationMode + ")");
        return 0;
    }
}
