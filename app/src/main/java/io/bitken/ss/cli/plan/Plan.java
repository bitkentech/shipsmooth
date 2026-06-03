package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code plan} noun group: bundles the plan-level subcommands ({@code init},
 * {@code show}, {@code update}) under one parent, mirroring {@code ledger}.
 * Leaves are constructed by {@code CommandTree} and passed in, so the group
 * stays free of gateway wiring.
 */
public class Plan implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public Plan(HasSpec... subcommands) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("plan");
        this.spec.usageMessage().description("Plan-level commands (init, show, update).");
        for (HasSpec sub : subcommands) {
            this.spec.addSubcommand(sub.getSpec().name(), sub.getSpec());
        }
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return 0;
    }
}
