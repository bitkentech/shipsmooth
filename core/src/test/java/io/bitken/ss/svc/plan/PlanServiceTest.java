package io.bitken.ss.svc.plan;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlanServiceTest {

    @TempDir
    Path tempDir;

    private PlanService planService() {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(tempDir);
        TaskStore xml = new TaskStore(locator);
        NewPlan newPlan = new NewPlan(new PlanNumbers(locator), new GitState(tempDir), locator);
        return new PlanService(xml, newPlan);
    }

    @Test
    public void updateTaskStatusMutatesXml() throws Exception {
        PlanService svc = planService();
        var tasks = List.of(new TaskStore.Task(1, "do the thing", "low"));
        svc.initPlan(1, "plan-1-v1", tasks);

        svc.updateTaskStatus(1, 1, "agent-coded");

        PlanTasks plan = svc.loadPlan(1);
        assertEquals("agent-coded",
            plan.getTasks().getTask().get(0).getStatus().value());
    }

    @Test
    public void addCommentMutatesXml() throws Exception {
        PlanService svc = planService();
        svc.initPlan(1, "plan-1-v1", List.of(new TaskStore.Task(1, "a task", "low")));

        svc.addComment(1, 1, "looks good");

        PlanTasks plan = svc.loadPlan(1);
        assertEquals(1, plan.getTasks().getTask().get(0).getComments().getComment().size());
    }

    @Test
    public void addTaskAppendsToXmlAndReturnsNewId() throws Exception {
        PlanService svc = planService();
        svc.initPlan(2, "plan-2-v1", List.of(new TaskStore.Task(1, "first", "high")));

        int newId = svc.addTask(2, "second", "medium", "1", "plan-2-v1");

        assertEquals(2, newId, "returned id should be the appended task's id");
        PlanTasks plan = svc.loadPlan(2);
        assertEquals(2, plan.getTasks().getTask().size());
        assertEquals("second", plan.getTasks().getTask().get(1).getName());
        assertEquals("medium", plan.getTasks().getTask().get(1).getRisk());
    }

    @Test
    public void setTaskCommitMutatesXml() throws Exception {
        PlanService svc = planService();
        svc.initPlan(1, "plan-1-v1", List.of(new TaskStore.Task(1, "a task", "low")));

        svc.setTaskCommit(1, 1, "abc1234", null);

        PlanTasks plan = svc.loadPlan(1);
        assertEquals("abc1234", plan.getTasks().getTask().get(0).getCommit());
    }

    @Test
    public void mutationWritesNoLedgerSideChannel() throws Exception {
        PlanService svc = planService();
        svc.initPlan(1, "plan-1-v1", List.of(new TaskStore.Task(1, "a task", "low")));
        svc.updateTaskStatus(1, 1, "agent-coded");

        // XML mutation happens...
        assertEquals("agent-coded",
            svc.loadPlan(1).getTasks().getTask().get(0).getStatus().value());
        // ...and no ledger.jsonl / object store is ever created (subsystem removed).
        assertFalse(tempDir.resolve(".shipsmooth/ledger.jsonl").toFile().exists(),
            "no ledger.jsonl must be written");
        assertFalse(tempDir.resolve(".shipsmooth/objects").toFile().exists(),
            "no object store must be created");
    }
}
