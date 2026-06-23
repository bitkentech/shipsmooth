package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.PlanService;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class SetCommit implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final Provider<PlanService> planService;

    public SetCommit(Provider<PlanService> planService) {
        this.planService = planService;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("set-commit");
        spec.usageMessage().description("Set the commit hash for a task.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class)
            .paramLabel("PLAN_NUMBER").description("Plan number").build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(int.class)
            .paramLabel("TASK_ID").description("Task ID (integer)").build());
        spec.addOption(OptionSpec.builder("--commit").required(true).type(String.class)
            .paramLabel("HASH").description("Commit hash to record for the task").build());
        spec.addOption(OptionSpec.builder("--branch").type(String.class)
            .paramLabel("BRANCH").description("Branch the commit lives on").build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String commit = pr.matchedOption("commit").getValue();
        String branch = pr.matchedOptionValue("branch", null);

        planService.get().setTaskCommit(plan, task, commit, branch);
        System.out.println("Commit set for task " + task);
        return 0;
    }
}
