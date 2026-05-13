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
import java.util.Map;
import java.util.concurrent.Callable;

public class SetCommitCommand implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public SetCommitCommand() {
        spec = CommandSpec.wrapWithoutInspection(this);
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

        XmlService service = new XmlService();
        File file = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(file);
        service.setCommit(planTasks, task, commit);
        service.writePlanTasks(planTasks, file);
        System.out.println("Commit set for task " + task);

        try {
            LedgerService ledger = new LedgerService(Paths.get("."));
            ledger.ensureLedgerFile();
            String integrationMode = branch != null && branch.startsWith("agent-work/") ? "worktree" : "direct";
            Map<String, String> meta = branch != null && !branch.isBlank()
                ? Map.of("branch", branch, "commit_sha", commit, "integration_mode", integrationMode)
                : Map.of("commit_sha", commit, "integration_mode", integrationMode);
            ledger.record(Event.forTask(EventType.COMMIT_RECORDED, String.valueOf(task), commit, commit, meta));
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
        return 0;
    }
}