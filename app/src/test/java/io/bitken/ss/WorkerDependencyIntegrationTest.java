package io.bitken.ss;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.cli.Shipsmooth;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.gw.TaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the dependency chain: task 2 depends-on task 1.
 * Task 2's worktree must inherit task 1's committed files.
 */
public class WorkerDependencyIntegrationTest {

    private static final int PLAN_NUM = 994;
    private static final String TASK_1 = "1";
    private static final String TASK_2 = "2";

    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile  = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private final Path repoRoot = Paths.get(".");
    private final AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(repoRoot))
            .build();

    @BeforeEach
    void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(),
                "### Task 1: Foundation task [Low]\n### Task 2: Dependent task [Low]\n");

        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        List<TaskStore.Task> tasks = List.of(
                new TaskStore.Task(1, "Foundation task", "low"),
                new TaskStore.Task(2, "Dependent task", "low")
        );
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.setDependsOn(planTasks, 2, "1");
        xmlService.writePlanTasks(planTasks, xmlFile);

        EventLedger ledger = new EventLedger(repoRoot);
        ledger.ensureLedgerFile();

        cleanupWorktrees();
    }

    @AfterEach
    void tearDown() throws Exception {
        xmlFile.delete();
        mdFile.delete();
        cleanupWorktrees();
    }

    @Test
    void dependentTask_inheritsParentCommit() throws Exception {
        Shipsmooth cli = new Shipsmooth(app);
        EventLedger ledger = new EventLedger(repoRoot);
        int beforeCount = ledger.readHashes().size();

        // --- Task 1: full lifecycle ---
        assertEquals(0, cli.execute("--enable-experimental", "claim","--plan", String.valueOf(PLAN_NUM), "--task", TASK_1));
        assertEquals(0, cli.execute("--enable-experimental", "worker-init","--plan", String.valueOf(PLAN_NUM), "--task", TASK_1));

        Path wt1 = repoRoot.resolve(".agents/tasks/" + TASK_1).toAbsolutePath();
        assertTrue(wt1.toFile().isDirectory());
        Files.writeString(wt1.resolve("output-1.txt"), "task 1 output");

        assertEquals(0, cli.execute("--enable-experimental", "worker-finish","--plan", String.valueOf(PLAN_NUM), "--task", TASK_1));
        assertEquals(0, cli.execute("--enable-experimental", "worker-cleanup","--plan", String.valueOf(PLAN_NUM), "--task", TASK_1));

        // Resolve parent commit SHA from ledger (same logic as worker-base command)
        List<String> hashes = ledger.readHashes();
        String task1CommitSha = hashes.subList(beforeCount, hashes.size()).stream().map(h -> {
            try { return ledger.readEvent(h); } catch (IOException e) { throw new RuntimeException(e); }
        }).filter(ev -> EventType.COMMIT_RECORDED == ev.eventType() && TASK_1.equals(ev.taskId()))
                .map(Event::payload)
                .findFirst().orElseThrow(() -> new AssertionError("No COMMIT_RECORDED for task 1"));

        assertFalse(task1CommitSha.isBlank(), "task 1 commit SHA must not be blank");

        // --- Task 2: init with --base pointing to task 1's commit ---
        assertEquals(0, cli.execute("--enable-experimental", "claim","--plan", String.valueOf(PLAN_NUM), "--task", TASK_2));
        assertEquals(0, cli.execute("--enable-experimental", "worker-init","--plan", String.valueOf(PLAN_NUM), "--task", TASK_2,
                "--base", task1CommitSha));

        Path wt2 = repoRoot.resolve(".agents/tasks/" + TASK_2).toAbsolutePath();
        assertTrue(wt2.toFile().isDirectory(), "task 2 worktree should exist");

        // Task 2's worktree must contain task 1's file (inherited via --base)
        assertTrue(wt2.resolve("output-1.txt").toFile().exists(),
                "task 2 worktree must inherit output-1.txt from task 1's commit");

        Files.writeString(wt2.resolve("output-2.txt"), "task 2 output");

        assertEquals(0, cli.execute("--enable-experimental", "worker-finish","--plan", String.valueOf(PLAN_NUM), "--task", TASK_2));
        assertEquals(0, cli.execute("--enable-experimental", "worker-cleanup","--plan", String.valueOf(PLAN_NUM), "--task", TASK_2));

        // --- assertions ---
        assertFalse(wt1.toFile().exists(), "task 1 worktree should be gone");
        assertFalse(wt2.toFile().exists(), "task 2 worktree should be gone");

        // Both branches must still exist
        assertTrue(branchExists("agent-work/" + TASK_1), "branch agent-work/1 should survive");
        assertTrue(branchExists("agent-work/" + TASK_2), "branch agent-work/2 should survive");

        // Both XML <commit> fields populated
        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks result = xmlService.readPlanTasks(xmlFile);
        String commit1 = result.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == 1).findFirst().orElseThrow().getCommit();
        String commit2 = result.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == 2).findFirst().orElseThrow().getCommit();
        assertFalse(commit1.isBlank(), "task 1 XML <commit> should be populated");
        assertFalse(commit2.isBlank(), "task 2 XML <commit> should be populated");

        // depends-on round-trips through XML
        assertEquals("1", xmlService.getDependsOn(result, 2), "<depends-on> should round-trip");
    }

    // --- helpers ---

    private boolean branchExists(String branch) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "branch", "--list", branch)
                .directory(repoRoot.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        return out.contains(branch);
    }

    private void cleanupWorktrees() {
        for (String taskId : List.of(TASK_1, TASK_2)) {
            try {
                Path wt = repoRoot.resolve(".agents/tasks/" + taskId);
                if (wt.toFile().isDirectory()) {
                    new ProcessBuilder("git", "worktree", "remove", "--force", wt.toString())
                            .directory(repoRoot.toFile()).start().waitFor(30, TimeUnit.SECONDS);
                }
                if (branchExists("agent-work/" + taskId)) {
                    new ProcessBuilder("git", "branch", "-D", "agent-work/" + taskId)
                            .directory(repoRoot.toFile()).start().waitFor(10, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {}
        }
    }
}