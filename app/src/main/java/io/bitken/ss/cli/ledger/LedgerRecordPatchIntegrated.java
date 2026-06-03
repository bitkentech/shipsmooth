package io.bitken.ss.cli.ledger;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.Map;
import java.util.concurrent.Callable;

public class LedgerRecordPatchIntegrated implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final EventLedger ledger;

    public LedgerRecordPatchIntegrated(EventLedger ledger) {
        this.ledger = ledger;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("ledger-record-patch-integrated");
        this.spec.usageMessage().description("Write a PATCH_INTEGRATED event directly to the ledger (recovery use only).");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--commit").required(true).description("Integration branch commit SHA (the manual commit made in the worktree).").type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--agent-work-sha").required(true).description("Tip SHA of the agent-work/{task} branch.").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        var commit = (String) pr.matchedOption("commit").getValue();
        var agentWorkSha = (String) pr.matchedOption("agent-work-sha").getValue();

        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(
            EventType.PATCH_INTEGRATED, String.valueOf(task), null, commit,
            Map.of("agent_work_sha", agentWorkSha, "recovery", "true")
        ));
        System.out.println("PATCH_INTEGRATED written for task " + task + " (recovery=true, commit=" + commit + ")");
        return 0;
    }
}
