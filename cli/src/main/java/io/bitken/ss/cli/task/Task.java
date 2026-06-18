package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanService;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code task} noun group: bundles the per-task subcommands ({@code add},
 * {@code comment}, {@code deviation}, {@code status}, {@code set-commit}) under
 * one parent. Builds its own leaves in the constructor
 * from the gateways they need.
 */
public class Task implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public Task(PlanService planService, GitTags gitTags) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("task");
        this.spec.usageMessage().description("Manage individual tasks within a plan and record their progress.");
        addLeaves(spec,
            new AddTask(planService, gitTags),
            new AddComment(planService),
            new AddDeviation(planService),
            new UpdateStatus(planService),
            new SetCommit(planService));
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
            leaf.getSpec().mixinStandardHelpOptions(true);
            parent.addSubcommand(leaf.getSpec().name(), leaf.getSpec());
        }
    }
}
