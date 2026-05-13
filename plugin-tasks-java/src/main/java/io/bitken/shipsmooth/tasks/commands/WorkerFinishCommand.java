package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;

public class WorkerFinishCommand {

    public WorkerFinishCommand() {
    }

    public int execute(int plan, String task) {
        WorkflowServiceImpl workflow = new WorkflowServiceImpl(Paths.get("."));
        try {
            workflow.finalizeWorker(plan, task);
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Capture subagent diff, commit on worktree branch, record ledger events.");
        spec.addOption(
            OptionSpec.builder("--plan")
                .paramLabel("PLAN_NUMBER")
                .required(true)
                .description("Plan number")
                .type(int.class).build()
        );
        spec.addOption(
            OptionSpec.builder("--task")
                .paramLabel("TASK_ID")
                .required(true)
                .description("Task ID")
                .type(String.class).build()
        );
        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();
        return new WorkerFinishCommand().execute(plan, task);
    }
}