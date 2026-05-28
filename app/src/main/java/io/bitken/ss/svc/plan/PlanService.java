package io.bitken.ss.svc.plan;

import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.ledger.EventType;
import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PlanService {

    private final TaskStore taskStore;
    private final EventLedger ledger;

    public PlanService(TaskStore taskStore, EventLedger ledger) {
        this.taskStore = taskStore;
        this.ledger = ledger;
    }

    public void initPlan(int planId, String planVersion, List<TaskStore.Task> tasks) throws Exception {
        PlanTasks plan = taskStore.generatePlanTasks(planId, planVersion, tasks);
        taskStore.savePlan(planId, plan);
        recordBestEffort(() -> {
            ledger.ensureLedgerFile();
            for (var t : tasks) {
                ledger.record(Event.forTask(EventType.TASK_REGISTRATION, String.valueOf(t.id()),
                        null, t.name(), null));
            }
        });
    }

    public void updateTaskStatus(int planId, int taskId, String status) throws Exception {
        mutateAndRecord(planId, plan -> taskStore.updateTaskStatus(plan, taskId, status),
                Event.forTask(EventType.STATUS_UPDATED, String.valueOf(taskId),
                        null, "status=" + status, null));
    }

    public void setTaskCommit(int planId, int taskId, String commit, String branch) throws Exception {
        mutateAndRecord(planId, plan -> taskStore.setCommit(plan, taskId, commit), () -> {
            var integrationMode = branch != null && branch.startsWith("agent-work/") ? "worktree" : "direct";
            var meta = branch != null && !branch.isBlank()
                    ? Map.of("branch", branch, "commit_sha", commit, "integration_mode", integrationMode)
                    : Map.of("commit_sha", commit, "integration_mode", integrationMode);
            return Event.forTask(EventType.COMMIT_RECORDED, String.valueOf(taskId), commit, commit, meta);
        });
    }

    public void addComment(int planId, int taskId, String message) throws Exception {
        mutateAndRecord(planId, plan -> taskStore.addComment(plan, taskId, message),
                Event.forTask(EventType.COMMENT_ADDED, String.valueOf(taskId), null, message, null));
    }

    public void addDeviation(int planId, int taskId, String type, String message) throws Exception {
        mutateAndRecord(planId, plan -> taskStore.addDeviation(plan, taskId, type, message),
                Event.forTask(EventType.DEVIATION_ADDED, String.valueOf(taskId),
                        null, type + ": " + message, null));
    }

    public void projectUpdate(int planId, String status, Boolean blocked, String message) throws Exception {
        var payload = (status != null ? "status=" + status : "")
                + (Boolean.TRUE.equals(blocked) ? " blocked=true" : "")
                + (message != null ? " " + message : "");
        mutateAndRecord(planId, plan -> taskStore.projectUpdate(plan, status, blocked, message),
                Event.system(EventType.PROJECT_UPDATE, null, payload.strip(), null));
    }

    public String findCommitSha(String taskId) throws IOException {
        var ev = ledger.findLastEvent(taskId, EventType.COMMIT_RECORDED);
        if (ev == null) return null;
        return ev.metadata().getOrDefault("commit_sha", ev.payload());
    }

    public PlanTasks loadPlan(int planId) throws JAXBException {
        return taskStore.loadPlan(planId);
    }

    public String recordEvent(Event event) throws IOException {
        ledger.ensureLedgerFile();
        return ledger.record(event);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface XmlMutation {
        void apply(PlanTasks plan) throws Exception;
    }

    @FunctionalInterface
    private interface EventSupplier {
        Event get() throws Exception;
    }

    private void mutateAndRecord(int planId, XmlMutation mutation, Event event) throws Exception {
        mutateAndRecord(planId, mutation, () -> event);
    }

    private void mutateAndRecord(int planId, XmlMutation mutation, EventSupplier eventSupplier) throws Exception {
        var plan = taskStore.loadPlan(planId);
        mutation.apply(plan);
        taskStore.savePlan(planId, plan);
        recordBestEffort(() -> {
            ledger.ensureLedgerFile();
            ledger.record(eventSupplier.get());
        });
    }

    private void recordBestEffort(LedgerAction action) {
        try {
            action.run();
        } catch (Exception e) {
            System.err.println("Warning: ledger record failed (XML mutation preserved): " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface LedgerAction {
        void run() throws Exception;
    }
}
