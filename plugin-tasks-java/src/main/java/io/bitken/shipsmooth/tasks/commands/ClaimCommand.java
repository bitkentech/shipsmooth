package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

public class ClaimCommand implements Callable<Integer> {

    private CommandSpec spec;

    public CommandSpec getSpec() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Claim a task for subagent execution and record AGENT_START.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID").type(String.class).build());
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();

        var repoRoot = Paths.get(".");
        File xmlFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");

        XmlService xmlService = new XmlService();
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);

        boolean taskExists = planTasks.getTasks().getTask().stream()
            .anyMatch(t -> String.valueOf(t.getId()).equals(task));
        if (!taskExists) {
            System.err.println("Error: task " + task + " not found in plan " + plan);
            return 1;
        }

        WorktreeService git = new WorktreeService(repoRoot);
        String headSha = git.headSha();

        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.AGENT_START, task, headSha,
            "task claimed for subagent execution", Map.of("plan", String.valueOf(plan))));

        System.out.println("Task " + task + " claimed.");
        return 0;
    }
}