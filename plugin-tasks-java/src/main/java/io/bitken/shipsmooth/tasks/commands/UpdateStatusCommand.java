package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class UpdateStatusCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final LedgerService ledgerService;

    @Inject
    public UpdateStatusCommand(XmlService xmlService, LedgerService ledgerService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("update-status");
        this.spec.usageMessage().description("Update the status of a task.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--status").required(true).type(String.class).build());
        this.xmlService = xmlService;
        this.ledgerService = ledgerService;
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

        var file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        var planTasks = xmlService.readPlanTasks(file);
        xmlService.updateTaskStatus(planTasks, task, status);
        xmlService.writePlanTasks(planTasks, file);
        System.out.println("Task " + task + " status set to \"" + status + "\"");

        try {
            ledgerService.ensureLedgerFile();
            ledgerService.record(Event.forTask(EventType.STATUS_UPDATED, String.valueOf(task), null, "status=" + status, null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}
