package io.bitken.ss.cli;

import io.bitken.ss.Build;
import io.bitken.ss.cli.*;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.conf.FeatureFlags;
import io.bitken.ss.workflow.WorkflowService;
import io.bitken.ss.workflow.WorkflowServiceImpl;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import io.bitken.ss.cli.HasSpec;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class Shipsmooth {

    private static final String ENABLE_EXPERIMENTAL_FLAG = "--enable-experimental";

    private final CommandLine cmd;
    private final CommandSpec rootSpec;
    private final Integrate integrateCmd;
    private final List<Callable<?>> experimentalCommands = new ArrayList<>();

    public Shipsmooth(AppComponents app) {
        TaskStore taskStore = app.taskStore();
        EventLedger ledger = app.eventLedger();
        PlanService planService = app.planService();
        WorktreeService worktree = app.worktreeService();
        WorkflowService workflow = app.workflowService();
        WorkflowServiceImpl workflowImpl = app.workflowServiceImpl();

        integrateCmd = new Integrate(workflow);

        rootSpec = CommandSpec.wrapWithoutInspection(this);
        rootSpec.name("shipsmooth");
        rootSpec.usageMessage().description("CLI to manage tasks, subagents and ledger for shipsmooth");
        rootSpec.version("0.1.0");
        rootSpec.addMixin("standardHelpOptions", CommandSpec.forAnnotatedObject(new Object() {
            @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
            boolean help;

            @CommandLine.Option(names = {"-V", "--version"}, versionHelp = true, description = "Print version information and exit.")
            boolean version;

            // Build is generated at compile time from
            // src/main/java-templates/io/bitken/shipsmooth/tasks/Build.java
            // by templating-maven-plugin (see pom.xml). The output lands in
            // target/generated-sources/java-templates/ and is on the compile path.
            @CommandLine.Option(names = ENABLE_EXPERIMENTAL_FLAG,
                description = "Enable experimental subcommands.",
                hidden = !Build.EXPERIMENTAL_BUILD)
            boolean enableExperimental;
        }));

        for (Callable<?> command : buildCommands(taskStore, ledger, planService, worktree, workflow, workflowImpl)) {
            if (command instanceof FeatureFlags ff && ff.isExperimental()) {
                experimentalCommands.add(command);
            } else {
                CommandSpec subSpec = ((HasSpec) command).getSpec();
                rootSpec.addSubcommand(subSpec.name(), subSpec);
            }
        }

        cmd = new CommandLine(rootSpec);
    }

    private Callable<?>[] buildCommands(TaskStore xml, EventLedger ledger, PlanService planService,
            WorktreeService worktree, WorkflowService workflow, WorkflowServiceImpl workflowImpl) {
        return new Callable<?>[] {
            new Init(planService, xml),
            new AddComment(planService),
            new AddDeviation(planService),
            new Claim(xml, worktree, ledger),
            integrateCmd,
            new Ledger(ledger),
            new LedgerRecordCommit(ledger),
            new LedgerRecordPatchIntegrated(ledger),
            new LedgerResolverComplete(ledger),
            new LedgerWatch(),
            new ProjectUpdate(planService),
            new SetCommit(planService),
            new Show(xml),
            new UpdateStatus(planService),
            new WorkerBase(planService, xml),
            new WorkerCleanup(worktree, ledger),
            new WorkerFinish(workflowImpl),
            new WorkerInit(workflow),
        };
    }

    /** Test seam for integration command. */
    public Integrate integrateCommand() {
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

        int exitCode = new Shipsmooth(app).execute(args);
        System.exit(exitCode);
    }
}