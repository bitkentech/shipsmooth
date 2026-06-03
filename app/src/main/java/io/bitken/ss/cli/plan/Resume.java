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
 * <p>Prints: XML task file present check, plan task summary, and any
 * integration worktrees for this plan. Replaces the four-command bash block
 * in phase2-execute.
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
        int plan = spec.commandLine().getParseResult().<Integer>matchedOption("plan").getValue();

        if (!taskStore.planTasksFileExists(plan)) {
            System.out.println("ERROR: task file not found for plan " + plan
                + " — run: shipsmooth plan init --plan " + plan);
            return 1;
        }
        if (printTaskSummary(plan) != 0) return 1;
        printWorktrees(plan);
        return 0;
    }

    private int printTaskSummary(int plan) {
        try {
            PlanTasks planTasks = taskStore.loadPlan(plan);
            System.out.println("=== Task state ===");
            System.out.print(taskStore.formatPlanSummary(planTasks));
            return 0;
        } catch (Exception e) {
            System.out.println("ERROR reading plan XML: " + e.getMessage());
            return 1;
        }
    }

    private void printWorktrees(int plan) {
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
    }
}
