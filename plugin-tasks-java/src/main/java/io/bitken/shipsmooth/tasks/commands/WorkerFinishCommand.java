package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "worker-finish", description = "Capture subagent diff, commit on worktree branch, record ledger events.")
public class WorkerFinishCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private String task;

    @Override
    public Integer call() {
        WorkflowServiceImpl workflow = new WorkflowServiceImpl(Paths.get("."));
        try {
            workflow.finalizeWorker(plan, task);
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
        return 0;
    }
}