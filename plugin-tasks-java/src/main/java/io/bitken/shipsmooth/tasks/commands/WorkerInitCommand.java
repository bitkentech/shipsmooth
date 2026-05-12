package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "worker-init", description = "Create a git worktree for a subagent task.")
public class WorkerInitCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private String task;

    @Option(names = "--base", description = "Base commit SHA to branch from (defaults to HEAD)")
    private String base;

    @Override
    public Integer call() {
        WorkflowServiceImpl workflow = new WorkflowServiceImpl(Paths.get("."));
        try {
            java.nio.file.Path worktreePath = workflow.initializeWorker(plan, task, base);
            System.out.println(worktreePath);
            return 0;
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
    }
}
