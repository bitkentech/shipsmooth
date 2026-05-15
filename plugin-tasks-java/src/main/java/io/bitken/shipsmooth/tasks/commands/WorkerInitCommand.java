package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class WorkerInitCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final WorkflowService workflow;

    @Inject
    public WorkerInitCommand(WorkflowService workflow) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("worker-init");
        this.spec.usageMessage().description("Create a git worktree for a subagent task.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--base").description("Base commit SHA to branch from (defaults to HEAD)").type(String.class).build());
        this.workflow = workflow;
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        var task = (String) pr.matchedOption("task").getValue();
        var base = (String) pr.matchedOptionValue("base", null);

        try {
            var worktreePath = workflow.initializeWorker(plan, task, base);
            System.out.println(worktreePath);
            return 0;
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
    }
}
