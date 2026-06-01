package io.bitken.ss.cli.worker;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.workflow.WorkflowException;
import io.bitken.ss.workflow.WorkflowServiceImpl;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class WorkerFinish implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final WorkflowServiceImpl workflow;

    @Inject
    public WorkerFinish(WorkflowServiceImpl workflow) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.workflow = workflow;
        this.spec.name("worker-finish");
        this.spec.usageMessage().description("Capture subagent diff, commit on worktree branch, record ledger events.");
        this.spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();

        try {
            workflow.finalizeWorker(plan, task);
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
        return 0;
    }
}
