package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "worker-init", description = "Create a git worktree for a subagent task.")
public class WorkerInitCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private String task;

    @Override
    public Integer call() throws Exception {
        Path repoRoot = Paths.get(".");
        WorktreeService git = new WorktreeService(repoRoot);

        String worktreeRel = ".agents/tasks/" + task;
        String branch = "agent-work/" + task;

        if (git.worktreeExists(worktreeRel)) {
            System.err.println("Error: worktree already exists at " + worktreeRel);
            return 1;
        }

        git.addWorktree(worktreeRel, branch);

        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.WORKTREE_CREATED, task, git.headSha(), worktreeRel,
                Map.of("branch", branch, "worktree_rel", worktreeRel)));

        Path worktreePath = repoRoot.resolve(worktreeRel).toAbsolutePath();
        System.out.println(worktreePath);
        return 0;
    }
}
