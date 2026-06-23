package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class Show implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final Provider<TaskStore> xmlService;

    public Show(Provider<TaskStore> xmlService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("show");
        this.spec.usageMessage().description("Show plan tasks.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class)
            .paramLabel("PLAN_NUMBER").description("Plan number").build());
        this.xmlService = xmlService;
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();

        TaskStore taskStore = xmlService.get();
        PlanTasks planTasks = taskStore.loadPlan(plan);
        System.out.print(taskStore.formatPlanSummary(planTasks));
        return 0;
    }
}
