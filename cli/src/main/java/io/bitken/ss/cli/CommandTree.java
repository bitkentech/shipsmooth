package io.bitken.ss.cli;

import io.bitken.ss.Build;
import io.bitken.ss.cli.plan.Plan;
import io.bitken.ss.cli.store.Store;
import io.bitken.ss.cli.task.Task;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.FeatureFlags;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

/**
 * Builds the picocli root command for shipsmooth, registering each subcommand
 * only when it is non-experimental or experimental mode is enabled.
 */
class CommandTree {

    private final CommandLine commandLine;

    /**
     * Builds the full command tree. It is comprehensive regardless of whether the store is
     * settled: plan/task leaves hold {@link jakarta.inject.Provider Provider}s of the
     * state-dependent services and only resolve a state root when their {@code call()} runs,
     * so they can be constructed (and shown in {@code --help}) even on an unsettled project.
     * The resolve-gate in {@link Shipsmooth} stops a state-dependent command from actually
     * dispatching while unsettled.
     */
    CommandTree(AppComponents app) {
        ExperimentalMode experimentalMode = app.experimentalMode();

        CommandSpec rootSpec = buildRootSpec();
        for (Callable<?> command : buildCommands(app)) {
            if (!isExperimental(command) || experimentalMode.enabled()) {
                register(rootSpec, command);
            }
        }
        commandLine = new CommandLine(rootSpec);
    }

    CommandLine commandLine() {
        return commandLine;
    }

    private static Callable<?>[] buildCommands(AppComponents app) {
        // Providers defer the state-root touch to call(), so the whole tree constructs
        // even on a clean first run; the gate handles dispatch-time gating.
        Plan plan = new Plan(app.planService(), app.taskStore(), app.gitTags(), app.gitState());
        Task task = new Task(app.planService(), app.gitTags());
        Store store = new Store();
        return new Callable<?>[] {
            plan,
            task,
            store,
        };
    }

    private static CommandSpec buildRootSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.name("shipsmooth");
        // Description must not name any experimental surface — it shows in prod
        // --help where experimental commands are hidden (plan-75 no-leakage rule).
        spec.usageMessage().description("CLI to manage plans and tasks for shipsmooth");
        spec.version(Build.VERSION);
        spec.mixinStandardHelpOptions(true);
        // Build is generated at compile time from
        // src/main/java-templates/io/bitken/shipsmooth/tasks/Build.java
        // by templating-maven-plugin (see pom.xml). The output lands in
        // target/generated-sources/java-templates/ and is on the compile path.
        spec.addOption(OptionSpec.builder(ExperimentalMode.FLAG)
            .type(boolean.class)
            .description("Enable experimental subcommands.")
            .hidden(!Build.EXPERIMENTAL_BUILD)
            .build());
        return spec;
    }

    private static boolean isExperimental(Callable<?> command) {
        return command instanceof FeatureFlags ff && ff.isExperimental();
    }

    private static void register(CommandSpec rootSpec, Callable<?> command) {
        CommandSpec subSpec = ((HasSpec) command).getSpec();
        subSpec.mixinStandardHelpOptions(true);
        rootSpec.addSubcommand(subSpec.name(), subSpec);
    }
}
