package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.PlanService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class UpdateStatus implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;

    public UpdateStatus(PlanService planService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("status");
        this.spec.usageMessage().description("Update the status of a task.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--status").required(true).type(String.class).build());
        this.planService = planService;
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String status = pr.matchedOption("status").getValue();

        try {
            io.bitken.ss.jaxb.TaskStatusType.fromValue(status);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: invalid status \"" + status + "\". Allowed values: "
                    + java.util.Arrays.toString(io.bitken.ss.jaxb.TaskStatusType.values()));
            return 2;
        }

        planService.updateTaskStatus(plan, task, status);
        System.out.println("Task " + task + " status set to \"" + status + "\"");
        return 0;
    }
}
