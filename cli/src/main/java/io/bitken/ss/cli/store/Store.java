package io.bitken.ss.cli.store;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.cli.conf.ds.ConfigWriter;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * {@code store} noun group: commands that manage where a project's shipsmooth state lives.
 * Currently just {@code init} (act on a first-run choice). Builds its own leaves from the
 * gateways they need, like the other noun groups.
 *
 * <p>Runs without a settled store — it exists to settle one. Like every command it now
 * constructs unconditionally; it touches no state root, so it simply runs (it does not
 * resolve the locator, so the resolve-gate never fires for it).
 */
public class Store implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;

    public Store() {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("store");
        this.spec.usageMessage().description("Manage where this project's shipsmooth state lives.");
        addLeaves(spec,
            new Init(new ProjectDataStoreResolver(), new ConfigWriter()),
            new Info(new ProjectDataStoreResolver()));
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
