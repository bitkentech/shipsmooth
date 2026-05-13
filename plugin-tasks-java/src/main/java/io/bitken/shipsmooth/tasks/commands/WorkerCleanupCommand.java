package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;
import java.util.Map;

public class WorkerCleanupCommand {

    public WorkerCleanupCommand() {
    }

    public int execute(int plan, String task) throws Exception {
        var repoRoot = Paths.get(".");
        WorktreeService git = new WorktreeService(repoRoot);

        String worktreeRel = ".agents/tasks/" + task;
        String branch = "agent-work/" + task;

        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();

        Event commitEvent = ledger.findLastEvent(task, EventType.COMMIT_RECORDED);
        if (commitEvent == null) {
            System.err.println("Error: no COMMIT_RECORDED event found for task " + task
                + ". Run worker-finish before worker-cleanup.");
            return 1;
        }

        if (!git.worktreeExists(worktreeRel)) {
            System.err.println("Warning: worktree " + worktreeRel + " not found, recording CLEANUP anyway.");
        } else {
            git.removeWorktreeKeepBranch(worktreeRel);
        }
        ledger.record(Event.forTask(EventType.CLEANUP, task, git.headSha(),
            "worktree removed, branch retained", Map.of("branch", branch)));

        System.out.println("Worktree for task " + task + " cleaned up. Branch " + branch + " retained.");
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Remove the worktree for a task, keeping the branch ref.");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--task")
            .required(true)
            .type(String.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();
        try {
            return new WorkerCleanupCommand().execute(plan, task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}