package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import io.bitken.shipsmooth.tasks.commands.IntegrateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

// As of plan-37, WorkerInitCommand, WorkerFinishCommand, and IntegrateCommand
// route their orchestration through io.bitken.shipsmooth.tasks.workflow.WorkflowService.
// Other commands still call domain services directly; further migration is future work.
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

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TasksCli()).execute(args);
        System.exit(exitCode);
    }
}
