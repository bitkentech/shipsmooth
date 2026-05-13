package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TasksCliIntegrationTest {

    private static final int PLAN_NUM = 994;
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private TasksCli tasksCli;

    @BeforeEach
    public void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: CLI test task [High]\n");

        List<XmlService.Task> tasks = List.of(new XmlService.Task(1, "CLI test task", "high"));
        XmlService xmlService = new XmlService();
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        tasksCli = new TasksCli();
    }

    @AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void cliHelpRuns() {
        assertEquals(0, tasksCli.execute(new String[]{"--help"}));
    }

    @Test
    public void updateStatusViaCliRecordsLedgerEntry() throws Exception {
        LedgerService ledger = new LedgerService(Paths.get("."));
        int before = ledger.readHashes().size();

        int exit = tasksCli.execute("update-status", "--plan", String.valueOf(PLAN_NUM), "--task", "1", "--status", "agent-coded");
        assertEquals(0, exit);

        List<String> hashes = ledger.readHashes();
        assertEquals(before + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.STATUS_UPDATED, ev.eventType());
        assertEquals("1", ev.taskId());
        assertTrue(ev.payload().contains("agent-coded"));
    }

    @Test
    public void ledgerListViaCliExitsZero() throws Exception {
        int exit = tasksCli.execute("ledger", "list");
        assertEquals(0, exit);
    }

    @Test
    public void ledgerVerifyViaCliExitsZero() throws Exception {
        int exit = tasksCli.execute("ledger", "verify");
        assertEquals(0, exit);
    }
}
