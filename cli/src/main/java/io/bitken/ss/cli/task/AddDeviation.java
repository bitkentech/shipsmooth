package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.PlanService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class AddDeviation implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;

    public AddDeviation(PlanService planService) {
        this.planService = planService;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("deviation");
        this.spec.usageMessage().description("Add a deviation to a task.");
        this.spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID (integer)").type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--type").paramLabel("TYPE").required(true).description("Type of deviation: minor, major").type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--message").paramLabel("MESSAGE").required(true).description("The deviation message").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String type = pr.matchedOption("type").getValue();
        String message = pr.matchedOption("message").getValue();

        planService.addDeviation(plan, task, type, message);
        System.out.println("Deviation added to task " + task);
        return 0;
    }
}
