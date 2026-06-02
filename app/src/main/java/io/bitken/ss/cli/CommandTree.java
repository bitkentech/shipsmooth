package io.bitken.ss.cli;

import io.bitken.ss.Build;
import io.bitken.ss.cli.ledger.Ledger;
import io.bitken.ss.cli.ledger.LedgerRecordCommit;
import io.bitken.ss.cli.ledger.LedgerRecordPatchIntegrated;
import io.bitken.ss.cli.ledger.LedgerResolverComplete;
import io.bitken.ss.cli.ledger.LedgerWatch;
import io.bitken.ss.cli.plan.Init;
import io.bitken.ss.cli.plan.ProjectUpdate;
import io.bitken.ss.cli.plan.Show;
import io.bitken.ss.cli.task.AddComment;
import io.bitken.ss.cli.task.AddDeviation;
import io.bitken.ss.cli.task.SetCommit;
import io.bitken.ss.cli.task.UpdateStatus;
import io.bitken.ss.cli.worker.Claim;
import io.bitken.ss.cli.worker.WorkerBase;
import io.bitken.ss.cli.worker.WorkerCleanup;
import io.bitken.ss.cli.worker.WorkerFinish;
import io.bitken.ss.cli.worker.WorkerInit;
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
        ExperimentalMode mode = app.experimentalMode();
        integrate = new Integrate(app.workflowService());

        CommandSpec rootSpec = buildRootSpec();
        for (Callable<?> command : buildCommands(app, integrate)) {
            if (!isExperimental(command) || mode.enabled()) {
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
        return new Callable<?>[] {
            new Init(app.planService(), app.taskStore(), app.experimentalMode()),
            new AddComment(app.planService()),
            new AddDeviation(app.planService()),
            new Claim(app.taskStore(), app.worktreeService(), app.eventLedger()),
            integrate,
            new Ledger(app.eventLedger()),
            new LedgerRecordCommit(app.eventLedger()),
            new LedgerRecordPatchIntegrated(app.eventLedger()),
            new LedgerResolverComplete(app.eventLedger()),
            new LedgerWatch(),
            new ProjectUpdate(app.planService()),
            new SetCommit(app.planService()),
            new Show(app.taskStore()),
            new UpdateStatus(app.planService()),
            new WorkerBase(app.planService(), app.taskStore()),
            new WorkerCleanup(app.worktreeService(), app.eventLedger()),
            new WorkerFinish(app.workflowServiceImpl()),
            new WorkerInit(app.workflowService()),
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
        spec.addOption(OptionSpec.builder(ExperimentalMode.flag())
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
