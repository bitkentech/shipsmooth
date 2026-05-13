package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.io.File;

public class ShowCommand {

    public ShowCommand() {
    }

    public int execute(int plan) throws Exception {
        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        System.out.print(service.formatPlanSummary(planTasks));
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Show plan tasks.");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        try {
            return new ShowCommand().execute(plan);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}