package io.bitken.ss.cli;

import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.service.PlanService;
import io.bitken.ss.gw.TaskStore;

import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CommandsTest {

    private final int PLAN_NUM = 999;
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private final TaskStore xmlService = new TaskStore();
    private EventLedger ledgerService;
    private PlanService planService;

    @BeforeEach
    public void setUp() throws Exception {
        ledgerService = new EventLedger(Paths.get("."));
        planService = new PlanService(xmlService, ledgerService);
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: Test task [High]\n");

        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Test task", "high"));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);
    }

    @AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void testInitCommand() {
        xmlFile.delete();
        int exitCode = new CommandLine(new Init(planService, xmlService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", mdFile.getPath());
        assertEquals(0, exitCode);
        assertTrue(xmlFile.exists());
    }

    @Test
    public void testInitCommandFileNotFound() {
        int exitCode = new CommandLine(new Init(planService, xmlService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", "non-existent.md");
        assertEquals(1, exitCode);
    }

    @Test
    public void testUpdateStatusCommand() throws Exception {
        int exitCode = new CommandLine(new UpdateStatus(planService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--status", "in-progress");
        assertEquals(0, exitCode);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals("in-progress", planTasks.getTasks().getTask().get(0).getStatus().value());
    }

    @Test
    public void testAddCommentCommand() throws Exception {
        int exitCode = new CommandLine(new AddComment(planService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--message", "Test comment");
        assertEquals(0, exitCode);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals(1, planTasks.getTasks().getTask().get(0).getComments().getComment().size());
        assertEquals("Test comment", planTasks.getTasks().getTask().get(0).getComments().getComment().get(0).getMessage());
    }

    @Test
    public void testAddDeviationCommand() throws Exception {
        int exitCode = new CommandLine(new AddDeviation(planService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--type", "minor", "--message", "Test deviation");
        assertEquals(0, exitCode);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals(1, planTasks.getTasks().getTask().get(0).getDeviations().getDeviation().size());
        assertEquals("minor", planTasks.getTasks().getTask().get(0).getDeviations().getDeviation().get(0).getType().value());
        assertEquals("Test deviation", planTasks.getTasks().getTask().get(0).getDeviations().getDeviation().get(0).getMessage());
    }

    @Test
    public void testSetCommitCommand() throws Exception {
        int exitCode = new CommandLine(new SetCommit(planService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--commit", "abcdef");
        assertEquals(0, exitCode);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals("abcdef", planTasks.getTasks().getTask().get(0).getCommit());
    }

    @Test
    public void testProjectUpdateCommand() throws Exception {
        int exitCode = new CommandLine(new ProjectUpdate(planService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM), "--status", "in-review", "--blocked", "--message", "Test update");
        assertEquals(0, exitCode);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals("in-review", planTasks.getMetadata().getStatus().value());
        assertEquals(2, planTasks.getProjectUpdates().getUpdate().size());
        assertTrue(planTasks.getProjectUpdates().getUpdate().get(1).isBlocked());
        assertEquals("Test update", planTasks.getProjectUpdates().getUpdate().get(1).getMessage());
    }

    @Test
    public void testShowCommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            int exitCode = new CommandLine(new Show(xmlService).getSpec()).execute("--plan", String.valueOf(PLAN_NUM));
            assertEquals(0, exitCode);
            String output = out.toString();
            assertTrue(output.contains("Plan " + PLAN_NUM));
            assertTrue(output.contains("Test task"));
        } finally {
            System.setOut(originalOut);
        }
    }
}
