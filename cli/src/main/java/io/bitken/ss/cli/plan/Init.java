package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanMarkdownParser;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class Init implements Callable<Integer>, HasSpec {

    private static final int MAX_REPORTED_NEAR_MISSES = 10;

    private final CommandSpec spec;
    private final Provider<PlanService> planService;
    private final Provider<TaskStore> taskStore;
    private final GitTags gitTagService;
    private final PlanMarkdownParser parser;

    public Init(Provider<PlanService> planService, Provider<TaskStore> taskStore, GitTags gitTagService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.planService = planService;
        this.taskStore = taskStore;
        this.gitTagService = gitTagService;
        this.parser = new PlanMarkdownParser();

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
        var result = parser.parseWithDiagnostics(markdown);
        if (result.tasks().isEmpty()) {
            System.err.println("Error: no tasks found in " + tasksFrom + " — nothing written.");
            System.err.println("Expected task headings: ### Task N: Short task name [High|Medium|Low]");
            System.err.println("Optional dependency line (first body line after its heading): *Depends-on: 1,2*");
            reportNearMisses(result.diagnostics(), System.err);
            return 1;
        }
        var planVersion = gitTagService.getPlanVersion(plan);

        planService.get().initPlan(plan, planVersion, result.tasks());

        // Report the path via the resolved store (reflects the actual state root) rather than
        // a throwaway locator assuming in-repo-at-CWD.
        System.out.println("Written " + result.tasks().size() + " tasks to " + store.planTasksFile(plan).getPath());
        reportNearMisses(result.diagnostics(), System.out);

        return 0;
    }

    private void reportNearMisses(List<PlanMarkdownParser.Diagnostic> diagnostics, PrintStream out) {
        if (diagnostics.isEmpty()) {
            return;
        }
        out.println("Skipped " + diagnostics.size() + " line(s) that look like task headings but do not match the grammar:");
        for (var d : diagnostics.subList(0, Math.min(diagnostics.size(), MAX_REPORTED_NEAR_MISSES))) {
            out.println("  line " + d.line() + ": " + d.text() + "  <- " + d.reason());
        }
        if (diagnostics.size() > MAX_REPORTED_NEAR_MISSES) {
            out.println("  … and " + (diagnostics.size() - MAX_REPORTED_NEAR_MISSES) + " more");
        }
    }
}
