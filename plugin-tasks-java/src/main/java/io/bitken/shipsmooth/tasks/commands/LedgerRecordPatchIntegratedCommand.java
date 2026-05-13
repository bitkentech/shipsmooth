package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;
import java.util.Map;

public class LedgerRecordPatchIntegratedCommand {

    public LedgerRecordPatchIntegratedCommand() {
    }

    public int execute(int plan, int task, String commit, String agentWorkSha) throws Exception {
        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(
            EventType.PATCH_INTEGRATED, String.valueOf(task), null, commit,
            Map.of("agent_work_sha", agentWorkSha, "recovery", "true")
        ));
        System.out.println("PATCH_INTEGRATED written for task " + task + " (recovery=true, commit=" + commit + ")");
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Write a PATCH_INTEGRATED event directly to the ledger (recovery use only).");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--task")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--commit")
            .required(true)
            .description("Integration branch commit SHA (the manual commit made in the worktree).")
            .type(String.class).build());

        spec.addOption(OptionSpec.builder("--agent-work-sha")
            .required(true)
            .description("Tip SHA of the agent-work/{task} branch.")
            .type(String.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String commit = pr.matchedOption("commit").getValue();
        String agentWorkSha = pr.matchedOption("agent-work-sha").getValue();
        try {
            return new LedgerRecordPatchIntegratedCommand().execute(plan, task, commit, agentWorkSha);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}