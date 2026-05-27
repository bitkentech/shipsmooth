package io.bitken.ss.cli;

import io.bitken.ss.service.PlanService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class SetCommit implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;

    @Inject
    public SetCommit(PlanService planService) {
        this.planService = planService;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("set-commit");
        spec.usageMessage().description("Set the commit hash for a task.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--commit").required(true).type(String.class).build());
        spec.addOption(OptionSpec.builder("--branch").type(String.class).build());
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

        planService.setTaskCommit(plan, task, commit, branch);
        System.out.println("Commit set for task " + task);
        return 0;
    }
}
