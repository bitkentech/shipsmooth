package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class Init implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final Provider<PlanService> planService;
    private final Provider<TaskStore> taskStore;
    private final GitTags gitTagService;

    public Init(Provider<PlanService> planService, Provider<TaskStore> taskStore, GitTags gitTagService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.planService = planService;
        this.taskStore = taskStore;
        this.gitTagService = gitTagService;

        spec.name("init");
        spec.usageMessage().description("Initialize task tracking XML for a plan");
        spec.addOption(OptionSpec.builder("--plan").
            paramLabel("PLAN_NUMBER").
            required(true).description("Plan number").
            type(int.class).
            build());
        spec.addOption(OptionSpec.builder("--tasks-from").
            paramLabel("<Path to Markdown file>").
            required(true).
            description("Path to the plan markdown file").
            type(String.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String tasksFrom = pr.matchedOption("tasks-from").getValue();

        Path markdownPath = Paths.get(tasksFrom);
        if (!Files.exists(markdownPath)) {
            System.err.println("Plan file not found: " + tasksFrom);
            return 1;
        }
        TaskStore store = taskStore.get();
        var markdown = Files.readString(markdownPath);
        var tasks = store.parseTasksFromPlan(markdown);
        var planVersion = gitTagService.getPlanVersion(plan);

        planService.get().initPlan(plan, planVersion, tasks);

        // Report the path via the resolved store (reflects the actual state root) rather than
        // a throwaway locator assuming in-repo-at-CWD.
        System.out.println("Written " + tasks.size() + " tasks to " + store.planTasksFile(plan).getPath());

        return 0;
    }
}
