package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.util.concurrent.Callable;

public class ShowCommand implements Callable<Integer> {

    private CommandSpec spec;

    public CommandSpec getSpec() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Show plan tasks.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();

        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        System.out.print(service.formatPlanSummary(planTasks));
        return 0;
    }
}