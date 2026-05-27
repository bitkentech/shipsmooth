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
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class ProjectUpdate implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final LedgerService ledgerService;

    @Inject
    public ProjectUpdate(XmlService xmlService, LedgerService ledgerService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.xmlService = xmlService;
        this.ledgerService = ledgerService;

        this.spec.name("project-update");
        this.spec.usageMessage().description("Add a project update.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--status").type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--blocked").type(Boolean.class).build());
        this.spec.addOption(OptionSpec.builder("--message").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String status = pr.matchedOptionValue("status", null);
        Boolean blocked = pr.matchedOptionValue("blocked", null);
        String message = pr.matchedOptionValue("message", null);

        var file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        var planTasks = xmlService.readPlanTasks(file);
        xmlService.projectUpdate(planTasks, status, blocked, message);
        xmlService.writePlanTasks(planTasks, file);
        System.out.println("Project update added.");

        try {
            ledgerService.ensureLedgerFile();
            var payload = (status != null ? "status=" + status : "") +
                (Boolean.TRUE.equals(blocked) ? " blocked=true" : "") +
                (message != null ? " " + message : "");
            ledgerService.record(Event.system(EventType.PROJECT_UPDATE, null, payload.strip(), null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}
