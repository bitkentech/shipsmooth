package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import io.bitken.shipsmooth.tasks.di.AppComponent;
import io.bitken.shipsmooth.tasks.di.DaggerAppComponent;
import io.bitken.shipsmooth.tasks.di.ServicesModule;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import io.bitken.shipsmooth.tasks.commands.HasSpec;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class TasksCli {

    private final CommandLine cmd;
    private final IntegrateCommand integrateCommand = new IntegrateCommand();
    private final AppComponent app = DaggerAppComponent.builder()
        .servicesModule(new ServicesModule(Paths.get(".")))
        .build();

    public TasksCli() {
        CommandSpec spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("tasks");
        spec.usageMessage().description("CLI to manage tasks, subagents and ledger for shipsmooth");
        spec.version("0.1.0");
        spec.addMixin("standardHelpOptions", CommandSpec.forAnnotatedObject(new Object() {
            @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
            boolean help;

            @CommandLine.Option(names = {"-V", "--version"}, versionHelp = true, description = "Print version information and exit.")
            boolean version;
        }));

        Callable<?>[] commands = {
            new InitCommand(),
            app.addCommentCommand(),
            new AddDeviationCommand(),
            new ClaimCommand(),
            integrateCommand,
            new LedgerCommand(),
            new LedgerRecordCommitCommand(),
            new LedgerRecordPatchIntegratedCommand(),
            new LedgerResolverCompleteCommand(),
            new LedgerWatchCommand(),
            new ProjectUpdateCommand(),
            new SetCommitCommand(),
            new ShowCommand(),
            new UpdateStatusCommand(),
            new WorkerBaseCommand(),
            new WorkerCleanupCommand(),
            new WorkerFinishCommand(),
            new WorkerInitCommand(),
        };

        for (Callable<?> command : commands) {
            CommandSpec subSpec = ((HasSpec) command).getSpec();
            spec.addSubcommand(subSpec.name(), subSpec);
        }

        cmd = new CommandLine(spec);
    }

    /** Test seam for integration command. */
    public IntegrateCommand integrateCommand() {
        return integrateCommand;
    }

    public int execute(String... args) {
        return cmd.execute(args);
    }

    public static void main(String[] args) {
        int exitCode = new TasksCli().execute(args);
        System.exit(exitCode);
    }
}