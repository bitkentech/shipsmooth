package io.bitken.ss.cli.worker;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.workflow.WorkflowException;
import io.bitken.ss.workflow.WorkflowService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class WorkerInit implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final WorkflowService workflow;

    public WorkerInit(WorkflowService workflow) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("init");
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
