package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "worker-cleanup", description = "Remove the worktree for a task, keeping the branch ref.")
public class WorkerCleanupCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private String task;

    @Override
    public Integer call() throws Exception {
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
}
