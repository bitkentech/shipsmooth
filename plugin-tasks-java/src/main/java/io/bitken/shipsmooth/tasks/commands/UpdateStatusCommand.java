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

public class UpdateStatusCommand implements Callable<Integer> {

    private CommandSpec spec;

    public CommandSpec getSpec() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Update the status of a task.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--status").required(true).type(String.class).build());
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String status = pr.matchedOption("status").getValue();

        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        service.updateTaskStatus(planTasks, task, status);
        service.writePlanTasks(planTasks, file);
        System.out.println("Task " + task + " status set to \"" + status + "\"");

        try {
            LedgerService ledger = new LedgerService(Paths.get("."));
            ledger.ensureLedgerFile();
            ledger.record(Event.forTask(EventType.STATUS_UPDATED, String.valueOf(task), null, "status=" + status, null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}