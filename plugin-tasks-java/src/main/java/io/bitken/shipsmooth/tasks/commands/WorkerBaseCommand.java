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
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "worker-base", description = "Print the base commit SHA for a dependent task (from parent's COMMIT_RECORDED event).")
public class WorkerBaseCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private String task;

    @Override
    public Integer call() throws Exception {
        var repoRoot = Paths.get(".");
        XmlService xmlService = new XmlService();
        File xmlFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);

        String dependsOn = xmlService.getDependsOn(planTasks, Integer.parseInt(task));
        if (dependsOn.isBlank()) {
            System.err.println("worker-base: task " + task + " has no <depends-on> — use HEAD as base");
            return 1;
        }

        List<String> parentIds = List.of(dependsOn.split(",")).stream()
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        LedgerService ledger = new LedgerService(repoRoot);
        String latestSha = null;
        for (String parentId : parentIds) {
            Event ev = ledger.findLastEvent(parentId, EventType.COMMIT_RECORDED);
            if (ev == null) {
                System.err.println("worker-base: parent task " + parentId + " has no COMMIT_RECORDED event yet");
                return 1;
            }
            // payload of COMMIT_RECORDED is the commit SHA
            latestSha = ev.payload();
        }

        System.out.println(latestSha);
        return 0;
    }
}
