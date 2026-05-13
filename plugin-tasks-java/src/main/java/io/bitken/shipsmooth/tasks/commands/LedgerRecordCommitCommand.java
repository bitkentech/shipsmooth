package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;
import java.util.Map;

public class LedgerRecordCommitCommand {

    public LedgerRecordCommitCommand() {
    }

    public int execute(int plan, int task, String commit, String branch) throws Exception {
        String integrationMode = branch.startsWith("agent-work/") ? "worktree" : "direct";
        Map<String, String> meta = Map.of(
            "branch", branch,
            "commit_sha", commit,
            "integration_mode", integrationMode
        );
        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.COMMIT_RECORDED, String.valueOf(task), commit, commit, meta));
        System.out.println("COMMIT_RECORDED written for task " + task + " (integration_mode=" + integrationMode + ")");
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Write a COMMIT_RECORDED event directly to the ledger (recovery use only).");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--task")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--commit")
            .required(true)
            .type(String.class).build());

        spec.addOption(OptionSpec.builder("--branch")
            .required(true)
            .description("Must start with agent-work/ to write integration_mode=worktree.")
            .type(String.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String commit = pr.matchedOption("commit").getValue();
        String branch = pr.matchedOption("branch").getValue();
        try {
            return new LedgerRecordCommitCommand().execute(plan, task, commit, branch);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}