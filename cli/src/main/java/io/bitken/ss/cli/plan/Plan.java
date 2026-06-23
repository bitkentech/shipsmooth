package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.svc.plan.PlanService;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code plan} noun group: bundles the plan-level subcommands under one parent.
 * Builds its own leaves in the constructor. State-dependent services arrive as
 * {@link Provider}s, threaded down to the leaves that need them, so the group and its
 * leaves construct without a settled state root (the root is touched only in {@code call()}).
 */
public class Plan implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public Plan(Provider<PlanService> planService, Provider<TaskStore> taskStore, GitTags gitTags,
                GitState gitState) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("plan");
        this.spec.usageMessage().description("Manage plans: create, inspect, tag, and track their lifecycle.");
        addLeaves(spec,
            new Init(planService, taskStore, gitTags),
            new QuickStart(planService),
            new Show(taskStore),
            new ProjectUpdate(planService),
            new Preflight(gitState, gitTags),
            new Tag(gitTags),
            new Branch(gitState),
            new Resume(taskStore));
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
            leaf.getSpec().mixinStandardHelpOptions(true);
            parent.addSubcommand(leaf.getSpec().name(), leaf.getSpec());
        }
    }
}
