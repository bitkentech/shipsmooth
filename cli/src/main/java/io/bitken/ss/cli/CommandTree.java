package io.bitken.ss.cli;

import io.bitken.ss.Build;
import io.bitken.ss.cli.ledger.Ledger;
import io.bitken.ss.cli.plan.Plan;
import io.bitken.ss.cli.task.Task;
import io.bitken.ss.cli.worker.Claim;
import io.bitken.ss.cli.worker.Worker;
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

    private final Integrate integrate;
    private final CommandLine commandLine;

    CommandTree(AppComponents app) {
        ExperimentalMode experimentalMode = app.experimentalMode();
        integrate = new Integrate(app.workflowService());

        CommandSpec rootSpec = buildRootSpec();
        for (Callable<?> command : buildCommands(app, integrate)) {
            if (!isExperimental(command) || experimentalMode.enabled()) {
                register(rootSpec, command);
            }
        }
        commandLine = new CommandLine(rootSpec);
    }

    CommandLine commandLine() {
        return commandLine;
    }

    /** Test seam for the integration command. */
    Integrate integrate() {
        return integrate;
    }

    private static Callable<?>[] buildCommands(AppComponents app, Integrate integrate) {
        Plan plan = new Plan(app.planService(), app.taskStore(), app.gitTags(), app.gitState(), app.experimentalMode());
        Task task = new Task(app.planService(), app.gitTags());
        Worker worker = new Worker(app.planService(), app.taskStore(), app.workflowService(),
            app.workflowServiceImpl(), app.worktreeService(), app.eventLedger());
        return new Callable<?>[] {
            plan,
            task,
            worker,
            new Claim(app.taskStore(), app.worktreeService(), app.eventLedger()),
            integrate,
            new Ledger(app.eventLedger()),
        };
    }

    private static CommandSpec buildRootSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.name("shipsmooth");
        spec.usageMessage().description("CLI to manage tasks, subagents and ledger for shipsmooth");
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
