package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.commands.*;
import io.bitken.shipsmooth.tasks.di.AppComponents;
import io.bitken.shipsmooth.tasks.di.DaggerAppComponents;
import io.bitken.shipsmooth.tasks.di.ServicesModule;
import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import io.bitken.shipsmooth.tasks.stability.FeatureFlags;
import io.bitken.shipsmooth.tasks.workflow.WorkflowService;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import io.bitken.shipsmooth.tasks.commands.HasSpec;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class TasksCli {

    private static final String ENABLE_EXPERIMENTAL_FLAG = "--enable-experimental";

    private final CommandLine cmd;
    private final CommandSpec rootSpec;
    private final IntegrateCommand integrateCmd;
    private final List<Callable<?>> experimentalCommands = new ArrayList<>();

    public TasksCli(AppComponents app) {
        XmlService xml = app.xmlService();
        LedgerService ledger = app.ledgerService();
        WorktreeService worktree = app.worktreeService();
        WorkflowService workflow = app.workflowService();
        WorkflowServiceImpl workflowImpl = app.workflowServiceImpl();

        integrateCmd = new IntegrateCommand(workflow);

        rootSpec = CommandSpec.wrapWithoutInspection(this);
        rootSpec.name("tasks");
        rootSpec.usageMessage().description("CLI to manage tasks, subagents and ledger for shipsmooth");
        rootSpec.version("0.1.0");
        rootSpec.addMixin("standardHelpOptions", CommandSpec.forAnnotatedObject(new Object() {
            @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
            boolean help;

            @CommandLine.Option(names = {"-V", "--version"}, versionHelp = true, description = "Print version information and exit.")
            boolean version;

            @CommandLine.Option(names = ENABLE_EXPERIMENTAL_FLAG,
                description = "Enable experimental subcommands.",
                hidden = !Build.EXPERIMENTAL_BUILD)
            boolean enableExperimental;
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
            if (command instanceof FeatureFlags ff && ff.isExperimental()) {
                experimentalCommands.add(command);
            } else {
                CommandSpec subSpec = ((HasSpec) command).getSpec();
                rootSpec.addSubcommand(subSpec.name(), subSpec);
            }
        }

        cmd = new CommandLine(rootSpec);
    }

    /** Test seam for integration command. */
    public IntegrateCommand integrateCommand() {
        return integrateCmd;
    }

    public int execute(String... args) {
        if (probeEnableExperimental(args)) {
            registerExperimentals();
        }
        return cmd.execute(args);
    }

    private boolean probeEnableExperimental(String[] args) {
        CommandLine probe = new CommandLine(rootSpec);
        probe.setUnmatchedArgumentsAllowed(true);
        probe.setUnmatchedOptionsArePositionalParams(true);
        try {
            ParseResult result = probe.parseArgs(args);
            return result.matchedOptionValue(ENABLE_EXPERIMENTAL_FLAG, false);
        } catch (Exception e) {
            return false;
        }
    }

    private void registerExperimentals() {
        for (Callable<?> c : experimentalCommands) {
            CommandSpec sub = ((HasSpec) c).getSpec();
            if (rootSpec.subcommands().containsKey(sub.name())) continue;
            rootSpec.addSubcommand(sub.name(), sub);
        }
    }

    public static void main(String[] args) {
        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get(".")))
            .build();

        int exitCode = new TasksCli(app).execute(args);
        System.exit(exitCode);
    }
}