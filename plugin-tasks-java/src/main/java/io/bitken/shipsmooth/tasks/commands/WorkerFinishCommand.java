package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class WorkerFinishCommand implements Callable<Integer> {

    private final CommandSpec spec;

    public WorkerFinishCommand() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Capture subagent diff, commit on worktree branch, record ledger events.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();

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