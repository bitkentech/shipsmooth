package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Path;
import java.nio.file.Paths;

public class WorkerInitCommand {

    public WorkerInitCommand() {
    }

    public int execute(int plan, String task, String base) {
        WorkflowServiceImpl workflow = new WorkflowServiceImpl(Paths.get("."));
        try {
            Path worktreePath = workflow.initializeWorker(plan, task, base);
            System.out.println(worktreePath);
            return 0;
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Create a git worktree for a subagent task.");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--task")
            .required(true)
            .type(String.class).build());

        spec.addOption(OptionSpec.builder("--base")
            .description("Base commit SHA to branch from (defaults to HEAD)")
            .type(String.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();
        String base = pr.matchedOptionValue("base", null);
        return new WorkerInitCommand().execute(plan, task, base);
    }
}