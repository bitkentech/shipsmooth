package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

public class TasksCli {

    private final CommandLine cmd;
    private final IntegrateCommand integrateCommand = new IntegrateCommand();

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

        spec.addSubcommand("init", new InitCommand().getSpec());
        spec.addSubcommand("add-comment", new AddCommentCommand().getSpec());
        spec.addSubcommand("add-deviation", new AddDeviationCommand().getSpec());
        spec.addSubcommand("claim", new ClaimCommand().getSpec());
        spec.addSubcommand("integrate", integrateCommand.getSpec());
        spec.addSubcommand("ledger", new LedgerCommand().getSpec());
        spec.addSubcommand("ledger-record-commit", new LedgerRecordCommitCommand().getSpec());
        spec.addSubcommand("ledger-record-patch-integrated", new LedgerRecordPatchIntegratedCommand().getSpec());
        spec.addSubcommand("ledger-resolver-complete", new LedgerResolverCompleteCommand().getSpec());
        spec.addSubcommand("ledger-watch", new LedgerWatchCommand().getSpec());
        spec.addSubcommand("project-update", new ProjectUpdateCommand().getSpec());
        spec.addSubcommand("set-commit", new SetCommitCommand().getSpec());
        spec.addSubcommand("show", new ShowCommand().getSpec());
        spec.addSubcommand("update-status", new UpdateStatusCommand().getSpec());
        spec.addSubcommand("worker-base", new WorkerBaseCommand().getSpec());
        spec.addSubcommand("worker-cleanup", new WorkerCleanupCommand().getSpec());
        spec.addSubcommand("worker-finish", new WorkerFinishCommand().getSpec());
        spec.addSubcommand("worker-init", new WorkerInitCommand().getSpec());

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