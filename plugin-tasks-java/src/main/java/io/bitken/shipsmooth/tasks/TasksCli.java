package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import io.bitken.shipsmooth.tasks.commands.IntegrateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "tasks", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "CLI to manage tasks, subagents and ledger for shipsmooth",
        subcommands = {
            InitCommand.class,
            // TODO: Implement an AddTask Command?
            UpdateStatusCommand.class,
            AddCommentCommand.class,
            AddDeviationCommand.class,
            SetCommitCommand.class,
            ProjectUpdateCommand.class,
            ShowCommand.class,
            LedgerCommand.class,
            ClaimCommand.class,
            WorkerInitCommand.class,
            WorkerFinishCommand.class,
            WorkerCleanupCommand.class,
            WorkerBaseCommand.class,
            IntegrateCommand.class,
            LedgerRecordCommitCommand.class,
            LedgerRecordPatchIntegratedCommand.class,
            LedgerResolverCompleteCommand.class,
            LedgerWatchCommand.class
        })
public class TasksCli implements Runnable {

    private final CommandLine cmd;
    private final InitCommand init;

    TasksCli() {
        CommandSpec spec = CommandSpec.create();
        init = new InitCommand();
        spec.addSubcommand("init", init.getSpec());
        cmd = new CommandLine(spec);
        cmd.getSubcommands().get("init").setExecutionStrategy(InitCommand::run);
        cmd.setExecutionStrategy(new CommandLine.RunFirst());
//        // TODO: Implement an AddTask Command?
//        UpdateStatusCommand updateStatus = new UpdateStatusCommand();
//        AddCommentCommand addComment = new AddCommentCommand();
//        AddDeviationCommand addDeviation = new AddDeviationCommand();
//        SetCommitCommand setCommit = new SetCommitCommand();
//        ProjectUpdateCommand projectUpdate = new ProjectUpdateCommand();
//        ShowCommand show = new ShowCommand();
//        ShowCommand.class,
//            LedgerCommand.class,
//            ClaimCommand.class,
//            WorkerInitCommand.class,
//            WorkerFinishCommand.class,
//            WorkerCleanupCommand.class,
//            WorkerBaseCommand.class,
//            IntegrateCommand.class,
//            LedgerRecordCommitCommand.class,
//            LedgerRecordPatchIntegratedCommand.class,
//            LedgerResolverCompleteCommand.class,
//            LedgerWatchCommand.class
    }

    @Override
    public void run() {
        cmd.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new TasksCli().cmd.execute(args);
        System.exit(exitCode);
    }
}
