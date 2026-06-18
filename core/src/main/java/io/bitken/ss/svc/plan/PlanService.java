package io.bitken.ss.svc.plan;

import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.util.List;

public class PlanService {

    private final TaskStore taskStore;
    private final NewPlan newPlan;

    public PlanService(TaskStore taskStore, NewPlan newPlan) {
        this.taskStore = taskStore;
        this.newPlan = newPlan;
    }

    /**
     * Thin-context quickstart: derive the next plan id, create the branch, and
     * write the stub plan file — no commit. Delegates to {@link NewPlan}; see
     * there for why no git-write collaborator is reachable.
     */
    public ScaffoldResult quickStart(String desc) throws ScaffoldException, IOException {
        return newPlan.scaffold(desc);
    }

    public void initPlan(int planId, String planVersion, List<TaskStore.Task> tasks) throws Exception {
        PlanTasks plan = taskStore.generatePlanTasks(planId, planVersion, tasks);
        taskStore.savePlan(planId, plan);
    }

    public void updateTaskStatus(int planId, int taskId, String status) throws Exception {
        mutate(planId, plan -> taskStore.updateTaskStatus(plan, taskId, status));
    }

    public void setTaskCommit(int planId, int taskId, String commit, String branch) throws Exception {
        mutate(planId, plan -> taskStore.setCommit(plan, taskId, commit));
    }

    public void addComment(int planId, int taskId, String message) throws Exception {
        mutate(planId, plan -> taskStore.addComment(plan, taskId, message));
    }

    public void addDeviation(int planId, int taskId, String type, String message) throws Exception {
        mutate(planId, plan -> taskStore.addDeviation(plan, taskId, type, message));
    }

    public void projectUpdate(int planId, String status, Boolean blocked, String message) throws Exception {
        mutate(planId, plan -> taskStore.projectUpdate(plan, status, blocked, message));
    }

    /**
     * Appends a new task to the plan's XML and returns the assigned id.
     */
    public int addTask(int planId, String name, String risk, String dependsOn, String planVersion) throws Exception {
        var plan = taskStore.loadPlan(planId);
        int newId = taskStore.addTask(plan, new TaskStore.Task(0, name, risk, dependsOn), planVersion);
        taskStore.savePlan(planId, plan);
        return newId;
    }

    public PlanTasks loadPlan(int planId) throws JAXBException {
        return taskStore.loadPlan(planId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface XmlMutation {
        void apply(PlanTasks plan) throws Exception;
    }

    private void mutate(int planId, XmlMutation mutation) throws Exception {
        var plan = taskStore.loadPlan(planId);
        mutation.apply(plan);
        taskStore.savePlan(planId, plan);
    }
}
