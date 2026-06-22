package io.bitken.ss.cli.store;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.cli.RunsWithoutSettledStore;
import io.bitken.ss.cli.conf.ds.ConfigWriter;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code store} noun group: commands that manage where a project's shipsmooth state lives.
 * Currently just {@code init} (act on a first-run choice). Builds its own leaves from the
 * gateways they need, like the other noun groups.
 *
 * <p>Runs without a settled store — it exists to settle one.
 */
public class Store implements Callable<Integer>, HasSpec, RunsWithoutSettledStore {

    @Override public boolean runsWithoutSettledStore() {
        return true;
    }


    private final CommandSpec spec;
    private final Init init;

    public Store() {
        this.init = new Init(new ProjectDataStoreResolver(), new ConfigWriter());
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("store");
        this.spec.usageMessage().description("Manage where this project's shipsmooth state lives.");
        addLeaves(spec, init);
    }

    public CommandSpec getSpec() {
        return spec;
    }

    /** The {@code init} leaf — {@code main} binds it to the resolution before execution. */
    public Init init() {
        return init;
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
