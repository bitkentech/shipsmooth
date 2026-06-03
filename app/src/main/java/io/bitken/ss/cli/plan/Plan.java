package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.svc.plan.PlanService;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code plan} noun group: bundles the plan-level subcommands under one parent,
 * mirroring {@code ledger}. Builds its own leaves in the constructor.
 */
public class Plan implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public Plan(PlanService planService, TaskStore taskStore, GitTags gitTags,
                GitState gitState, ExperimentalMode mode) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("plan");
        this.spec.usageMessage().description("Plan-level commands (init, show, update, preflight, tag, branch, resume).");
        addLeaves(spec,
            new Init(planService, taskStore, gitTags, mode),
            new Show(taskStore),
            new ProjectUpdate(planService),
            new Preflight(gitState, gitTags),
            new Tag(gitTags, gitState),
            new Branch(gitState),
            new Resume(taskStore, gitState));
    }

    @Override
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
