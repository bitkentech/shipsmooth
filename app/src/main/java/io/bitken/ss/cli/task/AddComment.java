package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.PlanService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class AddComment implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;

    public AddComment(PlanService planService) {
        this.planService = planService;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("add-comment");
        spec.usageMessage().description("Add a comment to a task.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID (integer)").type(int.class).build());
        spec.addOption(OptionSpec.builder("--message").paramLabel("MESSAGE").required(true).description("The comment text").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String message = pr.matchedOption("message").getValue();

        planService.addComment(plan, task, message);
        System.out.println("Comment added to task " + task);
        return 0;
    }
}
