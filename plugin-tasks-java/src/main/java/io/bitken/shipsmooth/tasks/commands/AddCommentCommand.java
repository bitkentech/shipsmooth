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

@Command(name = "add-comment", description = "Add a comment to a task.")
public class AddCommentCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private int task;

    @Option(names = "--message", required = true)
    private String message;

    @Override
    public Integer call() throws Exception {
        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        service.addComment(planTasks, task, message);
        service.writePlanTasks(planTasks, file);
        System.out.println("Comment added to task " + task);

        try {
            LedgerService ledger = new LedgerService(Paths.get("."));
            ledger.ensureLedgerFile();
            ledger.record(Event.forTask(EventType.COMMENT_ADDED, String.valueOf(task), null, message, null));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}
