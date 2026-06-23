package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

/**
 * {@code plan resume --plan N} — session-resume pre-flight composite.
 *
 * <p>Prints: XML task file present check and the plan task summary. Replaces the
 * four-command bash block in phase2-execute.
 */
public class Resume implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final Provider<TaskStore> taskStore;

    public Resume(Provider<TaskStore> taskStore) {
        this.taskStore = taskStore;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("resume");
        spec.usageMessage().description("Session-resume pre-flight: task state check.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
    }

    @Override
    public CommandSpec getSpec() { return spec; }

    @Override
    public Integer call() {
        int plan = spec.commandLine().getParseResult().<Integer>matchedOption("plan").getValue();

        TaskStore store = taskStore.get();
        if (!store.planTasksFileExists(plan)) {
            System.out.println("ERROR: task file not found for plan " + plan
                + " — run: shipsmooth plan init --plan " + plan);
            return 1;
        }
        return printTaskSummary(store, plan);
    }

    private int printTaskSummary(TaskStore store, int plan) {
        try {
            PlanTasks planTasks = store.loadPlan(plan);
            System.out.println("=== Task state ===");
            System.out.print(store.formatPlanSummary(planTasks));
            return 0;
        } catch (Exception e) {
            System.out.println("ERROR reading plan XML: " + e.getMessage());
            return 1;
        }
    }
}
