package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.io.File;
import java.nio.file.Paths;

public class ProjectUpdateCommand {

    public ProjectUpdateCommand() {
    }

    public int execute(int plan, String status, Boolean blocked, String message) throws Exception {
        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        service.projectUpdate(planTasks, status, blocked, message);
        service.writePlanTasks(planTasks, file);
        System.out.println("Project update added.");

        try {
            LedgerService ledger = new LedgerService(Paths.get("."));
            ledger.ensureLedgerFile();
            String payload = (status != null ? "status=" + status : "") +
                (Boolean.TRUE.equals(blocked) ? " blocked=true" : "") +
                (message != null ? " " + message : "");
            ledger.record(Event.system(EventType.PROJECT_UPDATE, null, payload.strip(), null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Add a project update.");

        spec.addOption(OptionSpec.builder("--plan")
            .required(true)
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--status")
            .type(String.class).build());

        spec.addOption(OptionSpec.builder("--blocked")
            .type(Boolean.class).build());

        spec.addOption(OptionSpec.builder("--message")
            .type(String.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        String status = pr.matchedOptionValue("status", null);
        Boolean blocked = pr.matchedOptionValue("blocked", null);
        String message = pr.matchedOptionValue("message", null);
        try {
            return new ProjectUpdateCommand().execute(plan, status, blocked, message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}