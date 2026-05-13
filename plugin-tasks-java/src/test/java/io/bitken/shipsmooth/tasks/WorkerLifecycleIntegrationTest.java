package io.bitken.shipsmooth.tasks;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the worker lifecycle:
 *   claim -> worker-init -> (subagent edits files) -> worker-finish -> worker-cleanup
 *
 * Runs against the actual git repo (Paths.get(".") == plugin-tasks-java/ subdir, which
 * is inside the real shipsmooth git repo), consistent with the other integration tests.
 */
public class WorkerLifecycleIntegrationTest {

    private static final int PLAN_NUM = 993;
    private static final String TASK_ID = "1";

    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile  = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private final Path repoRoot = Paths.get(".");

    @BeforeEach
    void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: Worker lifecycle test task [Low]\n");

        XmlService xmlService = new XmlService();
        List<XmlService.Task> tasks = List.of(new XmlService.Task(1, "Worker lifecycle test task", "low"));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();

        // Clean up any leftover worktree/branch from a previous failed run
        cleanupWorktree();
    }

    @AfterEach
    void tearDown() throws Exception {
        xmlFile.delete();
        mdFile.delete();
        cleanupWorktree();
    }

    /**
     * Happy path: claim -> worker-init -> write a file (simulating subagent) ->
     * worker-finish -> worker-cleanup.
     *
     * Asserts:
     * - Ledger has AGENT_START, WORKTREE_CREATED, PATCH_EMITTED, COMMIT_RECORDED, CLEANUP.
     * - Events are in lifecycle order.
     * - XML <commit> field is populated.
     * - Worktree directory is gone after cleanup.
     * - Branch agent-work/1 still exists after cleanup.
     */
    @Test
    void happyPath_workerLifecycleLeavesCommitAndBranch() throws Exception {
        TasksCli cli = new TasksCli();
        LedgerService ledger = new LedgerService(repoRoot);
        int beforeCount = ledger.readHashes().size();

        int exit;

        exit = cli.execute("claim", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, exit, "claim should exit 0");

        exit = cli.execute("worker-init", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, exit, "worker-init should exit 0");

        Path worktreePath = repoRoot.resolve(".agents/tasks/" + TASK_ID).toAbsolutePath();
        assertTrue(worktreePath.toFile().isDirectory(), "worktree directory should exist after worker-init");

        // Simulate subagent editing files inside the worktree (no git operations).
        Files.writeString(worktreePath.resolve("output.txt"), "subagent output");

        exit = cli.execute("worker-finish", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, exit, "worker-finish should exit 0");

        exit = cli.execute("worker-cleanup", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, exit, "worker-cleanup should exit 0");

        // --- assertions ---

        List<String> hashes = ledger.readHashes();
        // Only events added during this test (ledger may have prior entries)
        List<Event> newEvents = hashes.subList(beforeCount, hashes.size()).stream().map(h -> {
            try { return ledger.readEvent(h); } catch (IOException e) { throw new RuntimeException(e); }
        }).toList();

        List<EventType> types = newEvents.stream().map(Event::eventType).toList();
        assertTrue(types.contains(EventType.AGENT_START),      "ledger should have AGENT_START");
        assertTrue(types.contains(EventType.WORKTREE_CREATED), "ledger should have WORKTREE_CREATED");
        assertTrue(types.contains(EventType.PATCH_EMITTED),    "ledger should have PATCH_EMITTED");
        assertTrue(types.contains(EventType.COMMIT_RECORDED),  "ledger should have COMMIT_RECORDED");
        assertTrue(types.contains(EventType.CLEANUP),          "ledger should have CLEANUP");

        // Order: AGENT_START < WORKTREE_CREATED < PATCH_EMITTED < CLEANUP
        int idxStart = types.indexOf(EventType.AGENT_START);
        int idxWt    = types.indexOf(EventType.WORKTREE_CREATED);
        int idxPatch = types.indexOf(EventType.PATCH_EMITTED);
        int idxClean = types.indexOf(EventType.CLEANUP);
        assertTrue(idxStart < idxWt && idxWt < idxPatch && idxPatch < idxClean,
                "events must be in lifecycle order");

        // XML commit field populated
        XmlService xmlService = new XmlService();
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        String commit = planTasks.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == 1)
                .findFirst().orElseThrow()
                .getCommit();
        assertNotNull(commit, "XML <commit> should be populated");
        assertFalse(commit.isBlank(), "XML <commit> should not be blank");

        // Worktree directory gone
        assertFalse(worktreePath.toFile().exists(), "worktree dir should be removed after cleanup");

        // Branch agent-work/1 still exists
        Process p = gitInRepo("branch", "--list", "agent-work/" + TASK_ID);
        String branchOutput = new String(p.getInputStream().readAllBytes()).trim();
        assertTrue(branchOutput.contains("agent-work/" + TASK_ID),
                "branch agent-work/" + TASK_ID + " should survive cleanup");
    }

    /**
     * Guard test: if the subagent commits inside the worktree, worker-finish must
     * exit non-zero and add no PATCH_EMITTED or COMMIT_RECORDED event.
     */
    @Test
    void workerFinish_abortsWhenSubagentCommitted() throws Exception {
        TasksCli cli = new TasksCli();
        LedgerService ledger = new LedgerService(repoRoot);

        cli.execute("claim", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        cli.execute("worker-init", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);

        Path worktreePath = repoRoot.resolve(".agents/tasks/" + TASK_ID).toAbsolutePath();
        assertTrue(worktreePath.toFile().isDirectory(), "worktree should exist");

        // Simulate rogue subagent that commits inside the worktree.
        Files.writeString(worktreePath.resolve("rogue.txt"), "rogue");
        gitIn(worktreePath.toFile(), "add", "rogue.txt");
        gitIn(worktreePath.toFile(), "commit", "-q", "-m", "rogue commit");

        int beforeCount = ledger.readHashes().size();

        int exit = cli.execute("worker-finish", "--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertNotEquals(0, exit, "worker-finish should exit non-zero when subagent committed");

        int afterCount = ledger.readHashes().size();
        assertEquals(beforeCount, afterCount, "no ledger events should be added when guard fires");
    }

    // --- helpers ---

    private void cleanupWorktree() {
        try {
            // Remove worktree dir if it exists
            Path wt = repoRoot.resolve(".agents/tasks/" + TASK_ID);
            if (wt.toFile().isDirectory()) {
                runGit("worktree", "remove", "--force", wt.toString());
            }
            // Delete branch if it exists
            Process check = gitInRepo("branch", "--list", "agent-work/" + TASK_ID);
            String out = new String(check.getInputStream().readAllBytes()).trim();
            if (!out.isBlank()) {
                runGit("branch", "-D", "agent-work/" + TASK_ID);
            }
        } catch (Exception ignored) { }
    }

    private void runGit(String... args) throws IOException, InterruptedException {
        // resolve actual repo root (parent of plugin-tasks-java/)
        Path root = Paths.get(".").toAbsolutePath().normalize();
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(root.toFile())
                .redirectErrorStream(true).start();
        p.waitFor(30, TimeUnit.SECONDS);
    }

    private Process gitInRepo(String... args) throws IOException, InterruptedException {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).start();
        p.waitFor(10, TimeUnit.SECONDS);
        return p;
    }

    private void gitIn(File cwd, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IOException("git failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }
}