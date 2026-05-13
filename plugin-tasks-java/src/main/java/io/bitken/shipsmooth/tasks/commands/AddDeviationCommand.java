package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class AddDeviationCommand implements Callable<Integer> {

    private CommandSpec spec;

    public CommandSpec getSpec() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Add a deviation to a task.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID (integer)").type(int.class).build());
        spec.addOption(OptionSpec.builder("--type").paramLabel("TYPE").required(true).description("Type of deviation").type(String.class).build());
        spec.addOption(OptionSpec.builder("--message").paramLabel("MESSAGE").required(true).description("The deviation message").type(String.class).build());
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String type = pr.matchedOption("type").getValue();
        String message = pr.matchedOption("message").getValue();

        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        service.addDeviation(planTasks, task, type, message);
        service.writePlanTasks(planTasks, file);
        System.out.println("Deviation added to task " + task);

        try {
            LedgerService ledger = new LedgerService(Paths.get("."));
            ledger.ensureLedgerFile();
            ledger.record(Event.forTask(EventType.DEVIATION_ADDED, String.valueOf(task), null, type + ": " + message, null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}