package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import io.bitken.shipsmooth.tasks.di.AppComponents;
import io.bitken.shipsmooth.tasks.di.DaggerAppComponents;
import io.bitken.shipsmooth.tasks.di.ServicesModule;
import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import io.bitken.shipsmooth.tasks.workflow.WorkflowService;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import io.bitken.shipsmooth.tasks.commands.HasSpec;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class TasksCli {

    private final CommandLine cmd;
    private final IntegrateCommand integrateCmd;

    public TasksCli(AppComponents app) {
        XmlService xml = app.xmlService();
        LedgerService ledger = app.ledgerService();
        WorktreeService worktree = app.worktreeService();
        WorkflowService workflow = app.workflowService();
        WorkflowServiceImpl workflowImpl = app.workflowServiceImpl();

        integrateCmd = new IntegrateCommand(workflow);

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
            new InitCommand(xml, ledger),
            new AddCommentCommand(xml, ledger),
            new AddDeviationCommand(xml, ledger),
            new ClaimCommand(xml, worktree, ledger),
            integrateCmd,
            new LedgerCommand(ledger),
            new LedgerRecordCommitCommand(ledger),
            new LedgerRecordPatchIntegratedCommand(ledger),
            new LedgerResolverCompleteCommand(ledger),
            new LedgerWatchCommand(),
            new ProjectUpdateCommand(xml, ledger),
            new SetCommitCommand(xml, ledger),
            new ShowCommand(xml),
            new UpdateStatusCommand(xml, ledger),
            new WorkerBaseCommand(xml, ledger),
            new WorkerCleanupCommand(worktree, ledger),
            new WorkerFinishCommand(workflowImpl),
            new WorkerInitCommand(workflow),
        };

        for (Callable<?> command : commands) {
            CommandSpec subSpec = ((HasSpec) command).getSpec();
            spec.addSubcommand(subSpec.name(), subSpec);
        }

        cmd = new CommandLine(spec);
    }

    /** Test seam for integration command. */
    public IntegrateCommand integrateCommand() {
        return integrateCmd;
    }

    public int execute(String... args) {
        return cmd.execute(args);
    }

    public static void main(String[] args) {
        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get(".")))
            .build();

        int exitCode = new TasksCli(app).execute(args);
        System.exit(exitCode);
    }
}
