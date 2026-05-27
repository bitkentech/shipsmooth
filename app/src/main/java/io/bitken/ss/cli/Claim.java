package io.bitken.ss.cli;

import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.service.XmlService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

public class Claim implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final WorktreeService git;
    private final EventLedger ledger;

    @Inject
    public Claim(XmlService xmlService, WorktreeService git, EventLedger ledger) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("claim");
        this.spec.usageMessage().description("Claim a task for subagent execution and record AGENT_START.");
        this.spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").paramLabel("TASK_ID").required(true).description("Task ID").type(String.class).build());
        this.xmlService = xmlService;
        this.git = git;
        this.ledger = ledger;
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
        var xmlFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");

        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);

        boolean taskExists = planTasks.getTasks().getTask().stream()
            .anyMatch(t -> String.valueOf(t.getId()).equals(task));
        if (!taskExists) {
            System.err.println("Error: task " + task + " not found in plan " + plan);
            return 1;
        }

        String headSha = git.headSha();

        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.AGENT_START, task, headSha,
            "task claimed for subagent execution", Map.of("plan", String.valueOf(plan))));

        System.out.println("Task " + task + " claimed.");
        return 0;
    }
}
