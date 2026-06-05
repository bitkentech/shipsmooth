package io.bitken.ss.cli;
import io.bitken.ss.cli.plan.Init;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.gw.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InitLedgerTest {

    private static final int PLAN_NUM = 995;
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private final TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
    private EventLedger ledgerService;
    private PlanService planService;

    @BeforeEach
    public void setUp() throws Exception {
        ledgerService = new EventLedger(Paths.get("."));
        planService = new PlanService(xmlService, ledgerService, new ExperimentalMode(true));
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(),
                "### Task 1: Alpha task [High]\n### Task 2: Beta task [Low]\n");
        xmlFile.delete();
    }

    @org.junit.jupiter.api.AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void initCreatesObjectsDirAndLedgerFile() throws Exception {
        int exit = new CommandLine(new Init(planService, xmlService, new GitTags(java.nio.file.Paths.get(".")), new ExperimentalMode(true)).getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", mdFile.getPath());
        assertEquals(0, exit);

        assertTrue(Paths.get(".agents/objects").toFile().isDirectory());
        assertTrue(Paths.get(".agents/ledger.jsonl").toFile().exists());
    }

    @Test
    public void initEmitsOneTaskRegistrationEventPerTask() throws Exception {
        EventLedger ledger = new EventLedger(Paths.get("."));
        ledger.ensureLedgerFile();
        int before = ledger.readHashes().size();

        new CommandLine(new Init(planService, xmlService, new GitTags(java.nio.file.Paths.get(".")), new ExperimentalMode(true)).getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", mdFile.getPath());

        List<String> hashes = ledger.readHashes();
        // 2 tasks in the markdown → 2 TASK_REGISTRATION events
        assertEquals(before + 2, hashes.size());

        List<Event> newEvents = hashes.subList(before, hashes.size()).stream()
                .map(h -> { try { return ledger.readEvent(h); } catch (Exception e) { throw new RuntimeException(e); } })
                .toList();

        assertTrue(newEvents.stream().allMatch(e -> e.eventType() == EventType.TASK_REGISTRATION));
        assertTrue(newEvents.stream().anyMatch(e -> "1".equals(e.taskId())));
        assertTrue(newEvents.stream().anyMatch(e -> "2".equals(e.taskId())));
    }

    @Test
    public void initAppendsGitignoreEntriesIdempotently() throws Exception {
        Path gitignore = Paths.get(".gitignore");
        String originalContent = Files.exists(gitignore) ? Files.readString(gitignore) : null;

        try {
            // Run twice — entries must not be duplicated
            new CommandLine(new Init(planService, xmlService, new GitTags(java.nio.file.Paths.get(".")), new ExperimentalMode(true)).getSpec())
                    .execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", mdFile.getPath());
            xmlFile.delete();
            new CommandLine(new Init(planService, xmlService, new GitTags(java.nio.file.Paths.get(".")), new ExperimentalMode(true)).getSpec())
                    .execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", mdFile.getPath());

            String content = Files.readString(gitignore);
            assertEquals(1, content.lines().filter(l -> l.trim().equals(".agents/tasks/*")).count());
            assertEquals(1, content.lines().filter(l -> l.trim().equals(".agents/integration/*")).count());
            assertEquals(1, content.lines().filter(l -> l.trim().equals(".agents/ledger.jsonl")).count());
            assertEquals(1, content.lines().filter(l -> l.trim().equals(".agents/objects/")).count());
        } finally {
            if (originalContent == null) {
                Files.deleteIfExists(gitignore);
            } else {
                Files.writeString(gitignore, originalContent);
            }
        }
    }
}
