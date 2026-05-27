package io.bitken.shipsmooth.tasks.cmd;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.util.concurrent.Callable;

public class AddComment implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final LedgerService ledgerService;

    @Inject
    public AddComment(XmlService xmlService, LedgerService ledgerService) {
        this.xmlService = xmlService;
        this.ledgerService = ledgerService;
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

        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(file);
        xmlService.addComment(planTasks, task, message);
        xmlService.writePlanTasks(planTasks, file);
        System.out.println("Comment added to task " + task);

        try {
            ledgerService.ensureLedgerFile();
            ledgerService.record(Event.forTask(EventType.COMMENT_ADDED, String.valueOf(task), null, message, null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}