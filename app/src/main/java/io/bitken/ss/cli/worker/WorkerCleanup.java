package io.bitken.ss.cli.worker;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

public class WorkerCleanup implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final WorktreeService git;
    private final EventLedger ledger;

    @Inject
    public WorkerCleanup(WorktreeService git, EventLedger ledger) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("worker-cleanup");
        this.spec.usageMessage().description("Remove the worktree for a task, keeping the branch ref.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--task").required(true).type(String.class).build());
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

        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(Paths.get("."));
        var worktreeRel = locator.worktreeRel(task);
        var branch = locator.agentBranch(task);

        ledger.ensureLedgerFile();

        var commitEvent = ledger.findLastEvent(task, EventType.COMMIT_RECORDED);
        if (commitEvent == null) {
            System.err.println("Error: no COMMIT_RECORDED event found for task " + task
                + ". Run worker-finish before worker-cleanup.");
            return 1;
        }

        if (!git.worktreeExists(worktreeRel)) {
            System.err.println("Warning: worktree " + worktreeRel + " not found, recording CLEANUP anyway.");
        } else {
            git.removeWorktreeKeepBranch(worktreeRel);
        }
        ledger.record(Event.forTask(EventType.CLEANUP, task, git.headSha(),
            "worktree removed, branch retained", Map.of("branch", branch)));

        System.out.println("Worktree for task " + task + " cleaned up. Branch " + branch + " retained.");
        return 0;
    }
}
