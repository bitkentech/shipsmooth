package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

/**
 * Appends a new task to an existing plan's XML. The id is auto-assigned
 * (max existing id + 1) and {@code created-from} is resolved from the current
 * plan-version git tag, mirroring how {@code init} stamps freshly-generated tasks.
 */
public class AddTask implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;
    private final GitTags gitTags;

    public AddTask(PlanService planService) {
        this(planService, new GitTags());
    }

    public AddTask(PlanService planService, GitTags gitTags) {
        this.planService = planService;
        this.gitTags = gitTags;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("add-task");
        spec.usageMessage().description("Append a new task to an existing plan.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        spec.addOption(OptionSpec.builder("--name").paramLabel("TEXT").required(true).description("Task name").type(String.class).build());
        spec.addOption(OptionSpec.builder("--risk").paramLabel("RISK").description("Risk level (high|medium|low)").type(String.class).build());
        spec.addOption(OptionSpec.builder("--depends-on").paramLabel("IDS").description("Comma-separated task ids this task depends on (e.g. 1,3)").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String name = pr.matchedOption("name").getValue();
        String risk = pr.matchedOptionValue("risk", "");
        String dependsOn = pr.matchedOptionValue("depends-on", "");

        String planVersion = gitTags.getPlanVersion(plan);
        int id = planService.addTask(plan, name, risk, dependsOn, planVersion);
        System.out.println("Added task " + id + ": " + name);
        return 0;
    }
}
