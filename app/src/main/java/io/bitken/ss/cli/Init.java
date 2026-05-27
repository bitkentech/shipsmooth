package io.bitken.ss.cli;

import io.bitken.ss.AgentsLayout;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class Init implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;
    private final TaskStore xmlService;
    private final GitTags gitTagService;

    public Init(PlanService planService, TaskStore xmlService) {
        this(planService, xmlService, new GitTags());
    }

    @Inject
    public Init(PlanService planService, TaskStore xmlService, GitTags gitTagService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.planService = planService;
        this.xmlService = xmlService;
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
        var markdown = Files.readString(markdownPath);
        var tasks = xmlService.parseTasksFromPlan(markdown);
        var planVersion = gitTagService.getPlanVersion(plan);

        planService.initPlan(plan, planVersion, tasks);

        System.out.println("Written " + tasks.size() + " tasks to " + xmlService.planTasksFile(plan).getPath());

        bootstrapAgentsLayout(Paths.get("."));
        ensureGitignore(Paths.get("."));
        return 0;
    }

    private void bootstrapAgentsLayout(Path repoRoot) throws Exception {
        new AgentsLayout(repoRoot).bootstrap();
    }

    private void ensureGitignore(Path repoRoot) throws Exception {
        var gi = repoRoot.resolve(".gitignore");
        var content = Files.exists(gi) ? Files.readString(gi) : "";
        var updated = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) updated.append('\n');
        var changed = false;
        for (var entry : new String[]{".agents/tasks/*", ".agents/integration/*", ".agents/objects/", ".agents/ledger.jsonl"}) {
            if (content.lines().noneMatch(l -> l.trim().equals(entry))) {
                updated.append(entry).append('\n');
                changed = true;
            }
        }
        if (changed) {
            Files.writeString(gi, updated.toString());
        }
    }
}
