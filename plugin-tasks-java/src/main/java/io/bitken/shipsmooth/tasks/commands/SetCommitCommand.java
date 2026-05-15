package io.bitken.shipsmooth.tasks.commands;

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
import java.util.Map;
import java.util.concurrent.Callable;

public class SetCommitCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final LedgerService ledgerService;

    @Inject
    public SetCommitCommand(XmlService xmlService, LedgerService ledgerService) {
        this.xmlService = xmlService;
        this.ledgerService = ledgerService;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("set-commit");
        spec.usageMessage().description("Set the commit hash for a task.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--task").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--commit").required(true).type(String.class).build());
        spec.addOption(OptionSpec.builder("--branch").type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        int task = pr.matchedOption("task").getValue();
        String commit = pr.matchedOption("commit").getValue();
        String branch = pr.matchedOptionValue("branch", null);

        var file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        var planTasks = xmlService.readPlanTasks(file);
        xmlService.setCommit(planTasks, task, commit);
        xmlService.writePlanTasks(planTasks, file);
        System.out.println("Commit set for task " + task);

        try {
            ledgerService.ensureLedgerFile();
            var integrationMode = branch != null && branch.startsWith("agent-work/") ? "worktree" : "direct";
            var meta = branch != null && !branch.isBlank()
                ? Map.of("branch", branch, "commit_sha", commit, "integration_mode", integrationMode)
                : Map.of("commit_sha", commit, "integration_mode", integrationMode);
            ledgerService.record(Event.forTask(EventType.COMMIT_RECORDED, String.valueOf(task), commit, commit, meta));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}
