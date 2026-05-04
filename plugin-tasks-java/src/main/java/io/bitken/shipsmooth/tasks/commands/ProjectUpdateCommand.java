package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "project-update", description = "Add a project update.")
public class ProjectUpdateCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--status")
    private String status;

    @Option(names = "--blocked")
    private Boolean blocked;

    @Option(names = "--message")
    private String message;

    @Override
    public Integer call() throws Exception {
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
}
