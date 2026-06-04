package io.bitken.ss.cli.worker;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.conf.FeatureFlags;
import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.workflow.WorkflowService;
import io.bitken.ss.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code worker} noun group: bundles the parallel-execution worktree lifecycle
 * subcommands ({@code base}, {@code init}, {@code finish}, {@code cleanup}) under
 * one parent, mirroring {@code ledger}. Builds its own leaves in the constructor.
 * The whole group is experimental, so it implements {@link FeatureFlags}.
 */
public class Worker implements Callable<Integer>, HasSpec, FeatureFlags {

    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;

    public Worker(PlanService planService, TaskStore taskStore, WorkflowService workflow,
                  WorkflowServiceImpl workflowImpl, WorktreeService worktree, EventLedger ledger) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("worker");
        this.spec.usageMessage().description("Parallel-execution worktree lifecycle (base, init, finish, cleanup).");
        addLeaves(spec,
            new WorkerBase(planService, taskStore),
            new WorkerInit(workflow),
            new WorkerFinish(workflowImpl),
            new WorkerCleanup(worktree, ledger));
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return 0;
    }

    private static void addLeaves(CommandSpec parent, HasSpec... leaves) {
        for (HasSpec leaf : leaves) {
            parent.addSubcommand(leaf.getSpec().name(), leaf.getSpec());
        }
    }
}
