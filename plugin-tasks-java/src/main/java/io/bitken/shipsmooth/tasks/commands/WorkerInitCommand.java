package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class WorkerInitCommand implements Callable<Integer> {

    private CommandSpec spec;

    public CommandSpec getSpec() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Create a git worktree for a subagent task.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(String.class).build());
        spec.addOption(OptionSpec.builder("--base").description("Base commit SHA to branch from (defaults to HEAD)").type(String.class).build());
        return spec;
    }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();
        String base = pr.matchedOptionValue("base", null);

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
}