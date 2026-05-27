package io.bitken.ss.cli;

import io.bitken.ss.service.PlanService;
import io.bitken.ss.service.XmlService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class WorkerBase implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final PlanService planService;
    private final XmlService xmlService;

    @Inject
    public WorkerBase(PlanService planService, XmlService xmlService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("worker-base");
        this.spec.usageMessage().description("Print the base commit SHA for a dependent task (from parent's COMMIT_RECORDED event).");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(String.class).build());
        this.planService = planService;
        this.xmlService = xmlService;
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        var plan = (int) pr.matchedOption("plan").getValue();
        var task = (String) pr.matchedOption("task").getValue();

        var planTasks = planService.loadPlan(plan);

        var dependsOnStr = xmlService.getDependsOn(planTasks, Integer.parseInt(task));
        if (dependsOnStr.isBlank()) {
            System.err.println("worker-base: task " + task + " has no <depends-on> — use HEAD as base");
            return 1;
        }

        var parentIds = xmlService.parseDependsOn(dependsOnStr).stream()
            .map(String::valueOf).toList();

        String latestSha = null;
        for (var parentId : parentIds) {
            var sha = planService.findCommitSha(parentId);
            if (sha == null) {
                System.err.println("worker-base: parent task " + parentId + " has no COMMIT_RECORDED event yet");
                return 1;
            }
            if (sha.isBlank()) {
                System.err.println("worker-base: parent task " + parentId + " COMMIT_RECORDED event has no commit SHA");
                return 1;
            }
            latestSha = sha;
        }

        System.out.println(latestSha);
        return 0;
    }
}
