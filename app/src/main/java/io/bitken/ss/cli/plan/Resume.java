package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code plan resume --plan N} — session-resume pre-flight composite.
 *
 * <p>Prints: (1) whether the XML task file exists, (2) plan show summary,
 * (3) any worktrees associated with this plan. Replaces the four-command
 * bash block in phase2-execute.
 */
public class Resume implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final TaskStore taskStore;
    private final GitState gitState;

    public Resume(TaskStore taskStore, GitState gitState) {
        this.taskStore = taskStore;
        this.gitState = gitState;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("resume");
        spec.usageMessage().description("Session-resume pre-flight: task state + worktree check.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
    }

    @Override
    public CommandSpec getSpec() { return spec; }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();

        if (!taskStore.planTasksFileExists(plan)) {
            System.out.println("ERROR: task file not found for plan " + plan
                + " — run: shipsmooth plan init --plan " + plan);
            return 1;
        }

        try {
            PlanTasks planTasks = taskStore.loadPlan(plan);
            System.out.println("=== Task state ===");
            System.out.print(taskStore.formatPlanSummary(planTasks));
        } catch (Exception e) {
            System.out.println("ERROR reading plan XML: " + e.getMessage());
            return 1;
        }

        System.out.println("\n=== Worktrees for plan " + plan + " ===");
        String marker = "integration/plan-" + plan;
        List<String> relevant = gitState.worktreeList().stream()
                .filter(line -> line.contains(marker))
                .toList();
        if (relevant.isEmpty()) {
            System.out.println("(none)");
        } else {
            relevant.forEach(System.out::println);
        }
        return 0;
    }
}
