package io.bitken.shipsmooth.tasks.service;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.jaxb.TaskStatusType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class XmlServiceTest {

    @Test
    public void testReadPlanTasks() throws Exception {
        XmlService service = new XmlService();
        Path path = Paths.get("../.agents/plans/plan-27-tasks.xml");
        PlanTasks planTasks = service.readPlanTasks(path.toFile());
        
        assertNotNull(planTasks);
        assertEquals(27, planTasks.getPlan().intValue());
        assertEquals("plan-27-v1", planTasks.getPlanVersion());
    }

    @Test
    public void testParseTasksFromPlan() {
        XmlService service = new XmlService();
        String markdown = "### Task 1: Fix bug [High]\n" +
                "### Task 2: Refactor [Medium]\n" +
                "### Task 3: Test\n";
        List<XmlService.Task> tasks = service.parseTasksFromPlan(markdown);
        assertEquals(3, tasks.size());
        assertEquals(1, tasks.get(0).id());
        assertEquals("Fix bug", tasks.get(0).name());
        assertEquals("high", tasks.get(0).risk());
        assertEquals(3, tasks.get(2).id());
        assertEquals("Test", tasks.get(2).name());
        assertEquals("", tasks.get(2).risk());
    }

    @Test
    public void testUpdateOperations() throws Exception {
        XmlService service = new XmlService();
        List<XmlService.Task> tasks = List.of(new XmlService.Task(1, "Task 1", "high"));
        PlanTasks planTasks = service.generatePlanTasks(99, "plan-99-v1", tasks);
        
        service.updateTaskStatus(planTasks, 1, "in-progress");
        assertEquals(TaskStatusType.IN_PROGRESS, planTasks.getTasks().getTask().get(0).getStatus());
        
        service.addComment(planTasks, 1, "Hello world");
        assertEquals(1, planTasks.getTasks().getTask().get(0).getComments().getComment().size());
        assertEquals("Hello world", planTasks.getTasks().getTask().get(0).getComments().getComment().get(0).getMessage());
        
        service.addDeviation(planTasks, 1, "minor", "Oops");
        assertEquals(1, planTasks.getTasks().getTask().get(0).getDeviations().getDeviation().size());
        
        service.setCommit(planTasks, 1, "abcdef");
        assertEquals("abcdef", planTasks.getTasks().getTask().get(0).getCommit());
        
        service.projectUpdate(planTasks, "complete", true, "Finished");
        assertEquals(2, planTasks.getProjectUpdates().getUpdate().size());
        assertTrue(planTasks.getProjectUpdates().getUpdate().get(1).isBlocked());
        
        String summary = service.formatPlanSummary(planTasks);
        assertTrue(summary.contains("Plan 99 (plan-99-v1)"));
        assertTrue(summary.contains("Task 1"));
        assertTrue(summary.contains("abcdef"));
    }
}
