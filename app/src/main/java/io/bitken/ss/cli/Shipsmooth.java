package io.bitken.ss.cli;

import io.bitken.ss.Build;
import io.bitken.ss.cli.ledger.*;
import io.bitken.ss.cli.plan.Init;
import io.bitken.ss.cli.plan.ProjectUpdate;
import io.bitken.ss.cli.plan.Show;
import io.bitken.ss.cli.task.AddComment;
import io.bitken.ss.cli.task.AddDeviation;
import io.bitken.ss.cli.task.SetCommit;
import io.bitken.ss.cli.task.UpdateStatus;
import io.bitken.ss.cli.worker.*;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
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

import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class Shipsmooth {

    private static final String ENABLE_EXPERIMENTAL_FLAG = "--enable-experimental";

    private final CommandLine cmd;
    private final CommandSpec rootSpec;
    private final Integrate integrateCmd;
    private final String[] args;

    public Shipsmooth(AppComponents app, ExperimentalMode mode, String[] args) {
        this.args = args;
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

        for (Callable<?> command : buildCommands(taskStore, ledger, planService, worktree, workflow, workflowImpl, mode)) {
            if (!isExperimental(command) || mode.enabled()) {
                addSubcommand(command);
            }
        }

        cmd = new CommandLine(rootSpec);
    }

    private static boolean isExperimental(Callable<?> command) {
        return command instanceof FeatureFlags ff && ff.isExperimental();
    }

    private void addSubcommand(Callable<?> command) {
        CommandSpec subSpec = ((HasSpec) command).getSpec();
        subSpec.mixinStandardHelpOptions(true);
        rootSpec.addSubcommand(subSpec.name(), subSpec);
    }

    private Callable<?>[] buildCommands(TaskStore xml, EventLedger ledger, PlanService planService,
            WorktreeService worktree, WorkflowService workflow, WorkflowServiceImpl workflowImpl,
            ExperimentalMode mode) {
        return new Callable<?>[] {
            new Init(planService, xml, mode),
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

    public int execute() {
        return cmd.execute(args);
    }

    public static void main(String[] args) {
        ExperimentalMode mode = ExperimentalMode.fromArgs(args);
        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get("."), mode))
            .build();

        int exitCode = new Shipsmooth(app, mode, args).execute();
        System.exit(exitCode);
    }
}