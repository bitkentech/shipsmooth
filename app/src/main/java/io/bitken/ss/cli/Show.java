package io.bitken.ss.cli;

import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.util.concurrent.Callable;

public class Show implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final TaskStore xmlService;

    @Inject
    public Show(TaskStore xmlService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("show");
        this.spec.usageMessage().description("Show plan tasks.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.xmlService = xmlService;
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();

        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(file);
        System.out.print(xmlService.formatPlanSummary(planTasks));
        return 0;
    }
}
