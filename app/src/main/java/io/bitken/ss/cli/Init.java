package io.bitken.ss.cli;

import io.bitken.ss.AgentsLayout;
import io.bitken.ss.git.GitTagService;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.service.XmlService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class Init implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final XmlService xmlService;
    private final EventLedger ledgerService;
    private final GitTagService gitTagService;

    public Init(XmlService xmlService, EventLedger ledgerService) {
        this(xmlService, ledgerService, new GitTagService());
    }

    @Inject
    public Init(XmlService xmlService, EventLedger ledgerService, GitTagService gitTagService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.xmlService = xmlService;
        this.ledgerService = ledgerService;
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

        var planTasks = xmlService.generatePlanTasks(plan, planVersion, tasks);
        var outFile = xmlService.planTasksFile(plan);
        xmlService.writePlanTasks(planTasks, outFile);

        System.out.println("Written " + tasks.size() + " tasks to " + outFile.getPath());

        bootstrapAgentsLayout(Paths.get("."));
        ensureGitignore(Paths.get("."));
        recordTaskRegistrations(tasks, planVersion);
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

    private void recordTaskRegistrations(List<XmlService.Task> tasks, String planVersion) {
        try {
            for (var task : tasks) {
                ledgerService.record(Event.forTask(EventType.TASK_REGISTRATION, String.valueOf(task.id()),
                        null, task.name(), null));
            }
        } catch (Exception e) {
            System.err.println("Warning: ledger registration failed: " + e.getMessage());
        }
    }
}
