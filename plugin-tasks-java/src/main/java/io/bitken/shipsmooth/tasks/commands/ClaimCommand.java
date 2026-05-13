package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.WorktreeService;
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
import java.util.Map;

public class ClaimCommand {

    public ClaimCommand() {
    }

    public int execute(int plan, String task) throws Exception {
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

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Claim a task for subagent execution and record AGENT_START.");
        spec.addOption(
            OptionSpec.builder("--plan")
                .paramLabel("PLAN_NUMBER")
                .required(true)
                .description("Plan number")
                .type(int.class).build()
        );
        spec.addOption(
            OptionSpec.builder("--task")
                .paramLabel("TASK_ID")
                .required(true)
                .description("Task ID")
                .type(String.class).build()
        );
        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        String task = pr.matchedOption("task").getValue();
        try {
            return new ClaimCommand().execute(plan, task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}