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
import java.util.List;
import java.util.concurrent.Callable;

public class WorkerBaseCommand implements Callable<Integer> {

    private final CommandSpec spec;

    public WorkerBaseCommand() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Print the base commit SHA for a dependent task (from parent's COMMIT_RECORDED event).");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();

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
            String sha = ev.metadata().getOrDefault("commit_sha", ev.payload());
            if (sha == null || sha.isBlank()) {
                System.err.println("worker-base: parent task " + parentId + " COMMIT_RECORDED event has no commit SHA");
                return 1;
            }
            latestSha = sha;
        }

        System.out.println(latestSha);
        return 0;
    }
}