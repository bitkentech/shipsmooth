package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "init", description = "Initialize task tracking XML for a plan.")
public class InitCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true, description = "Plan number.")
    private int plan;

    @Option(names = "--tasks-from", required = true, description = "Path to the plan markdown file.")
    private String tasksFrom;

    @Override
    public Integer call() throws Exception {
        XmlService service = new XmlService();
        Path markdownPath = Paths.get(tasksFrom);
        if (!Files.exists(markdownPath)) {
            System.err.println("Plan file not found: " + tasksFrom);
            return 1;
        }
        String markdown = Files.readString(markdownPath);
        List<XmlService.Task> tasks = service.parseTasksFromPlan(markdown);
        String planVersion = service.getPlanVersion(plan);

        PlanTasks planTasks = service.generatePlanTasks(plan, planVersion, tasks);
        File outFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        service.writePlanTasks(planTasks, outFile);

        System.out.println("Written " + tasks.size() + " tasks to " + outFile.getPath());

        bootstrapAgentsLayout(Paths.get("."));
        ensureGitignore(Paths.get("."));
        recordTaskRegistrations(tasks, planVersion);

        return 0;
    }

    private void bootstrapAgentsLayout(Path repoRoot) throws Exception {
        Files.createDirectories(repoRoot.resolve(".agents").resolve("objects"));
        Path ledger = repoRoot.resolve(".agents").resolve("ledger.jsonl");
        if (!Files.exists(ledger)) {
            Files.createFile(ledger);
        }
    }

    private void ensureGitignore(Path repoRoot) throws Exception {
        Path gi = repoRoot.resolve(".gitignore");
        String content = Files.exists(gi) ? Files.readString(gi) : "";
        StringBuilder updated = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) updated.append('\n');
        boolean changed = false;
        for (String entry : new String[]{".agents/tasks/*", ".agents/integration/*", ".agents/objects/", ".agents/ledger.jsonl"}) {
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
            LedgerService ledger = new LedgerService(Paths.get("."));
            for (XmlService.Task task : tasks) {
                ledger.record(Event.forTask(EventType.TASK_REGISTRATION, String.valueOf(task.id()),
                        null, task.name(), null));
            }
        } catch (Exception e) {
            System.err.println("Warning: ledger registration failed: " + e.getMessage());
        }
    }
}
