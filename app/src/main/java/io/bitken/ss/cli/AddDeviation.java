package io.bitken.ss.cli;

import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.service.XmlService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class AddDeviation implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final EventLedger ledgerService;

    @Inject
    public AddDeviation(XmlService xmlService, EventLedger ledgerService) {
        this.xmlService = xmlService;
        this.ledgerService = ledgerService;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("add-deviation");
        this.spec.usageMessage().description("Add a deviation to a task.");
        this.spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID (integer)").type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--type").paramLabel("TYPE").required(true).description("Type of deviation").type(String.class).build());
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

        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(file);
        xmlService.addDeviation(planTasks, task, type, message);
        xmlService.writePlanTasks(planTasks, file);
        System.out.println("Deviation added to task " + task);

        try {
            ledgerService.ensureLedgerFile();
            ledgerService.record(Event.forTask(EventType.DEVIATION_ADDED, String.valueOf(task), null, type + ": " + message, null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}
