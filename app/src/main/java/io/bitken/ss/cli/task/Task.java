package io.bitken.ss.cli.task;

import io.bitken.ss.cli.HasSpec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code task} noun group: bundles the per-task subcommands ({@code add},
 * {@code comment}, {@code deviation}, {@code status}, {@code set-commit}) under
 * one parent, mirroring {@code ledger}. Leaves are constructed by
 * {@code CommandTree} and passed in.
 */
public class Task implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public Task(HasSpec... subcommands) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("task");
        this.spec.usageMessage().description("Per-task commands (add, comment, deviation, status, set-commit).");
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
