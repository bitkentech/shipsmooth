package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "tasks", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Task management CLI for shipsmooth.",
        subcommands = {
            InitCommand.class,
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
            WorkerCleanupCommand.class
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
