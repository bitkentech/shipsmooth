package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asserts that each mutating command records exactly one ledger entry with the correct type.
 * Uses the same real .agents/ directory as CommandsTest (cwd = repo root during Maven test).
 */
public class CommandLedgerTest {

    private static final int PLAN_NUM = 996;
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private final XmlService xmlService = new XmlService();

    private LedgerService ledger;
    private int ledgerSizeBefore;

    @BeforeEach
    public void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: Test task [High]\n");

        List<XmlService.Task> tasks = List.of(new XmlService.Task(1, "Test task", "high"));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        // Ensure ledger exists; record baseline size
        ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        ledgerSizeBefore = ledger.readHashes().size();
    }

    @AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void updateStatusRecordsStatusUpdatedEvent() throws Exception {
        new CommandLine(new UpdateStatusCommand().getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--status", "in-progress");

        List<String> hashes = ledger.readHashes();
        assertEquals(ledgerSizeBefore + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.STATUS_UPDATED, ev.eventType());
        assertEquals("1", ev.taskId());
        assertTrue(ev.payload().contains("in-progress"));
    }

    @Test
    public void addCommentRecordsCommentAddedEvent() throws Exception {
        new CommandLine(new AddCommentCommand(xmlService, ledger).getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--message", "draft ready");

        List<String> hashes = ledger.readHashes();
        assertEquals(ledgerSizeBefore + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.COMMENT_ADDED, ev.eventType());
        assertEquals("1", ev.taskId());
    }

    @Test
    public void addDeviationRecordsDeviationAddedEvent() throws Exception {
        new CommandLine(new AddDeviationCommand().getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--type", "minor", "--message", "split task");

        List<String> hashes = ledger.readHashes();
        assertEquals(ledgerSizeBefore + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.DEVIATION_ADDED, ev.eventType());
        assertEquals("1", ev.taskId());
    }

    @Test
    public void setCommitRecordsCommitRecordedEvent() throws Exception {
        new CommandLine(new SetCommitCommand().getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--task", "1", "--commit", "deadbeef");

        List<String> hashes = ledger.readHashes();
        assertEquals(ledgerSizeBefore + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.COMMIT_RECORDED, ev.eventType());
        assertEquals("1", ev.taskId());
        assertEquals("deadbeef", ev.baseCommitSha());
    }

    @Test
    public void projectUpdateRecordsProjectUpdateEvent() throws Exception {
        new CommandLine(new ProjectUpdateCommand().getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--status", "in-review", "--message", "all tasks done");

        List<String> hashes = ledger.readHashes();
        assertEquals(ledgerSizeBefore + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.PROJECT_UPDATE, ev.eventType());
        assertNull(ev.taskId());
        assertTrue(ev.payload().contains("in-review"));
    }
}
