package io.bitken.shipsmooth.tasks;

import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the worker lifecycle:
 *   claim -> worker-init -> (subagent edits files) -> worker-finish -> worker-cleanup
 *
 * These tests fail until Tasks 1-6 are implemented.
 */
public class WorkerLifecycleIntegrationTest {

    @TempDir
    Path tempDir;

    private static final int PLAN_NUM = 993;
    private File planDir;
    private File xmlFile;
    private File ledgerFile;

    @BeforeEach
    void setUp() throws Exception {
        // Bootstrap a real git repo in tempDir so worktree operations work.
        git("init", "-q");
        git("config", "user.email", "test@test.local");
        git("config", "user.name", "Test");
        // Create a seed commit so HEAD resolves.
        Path seed = tempDir.resolve("seed.txt");
        Files.writeString(seed, "seed");
        git("add", "seed.txt");
        git("commit", "-q", "-m", "init");

        // Bootstrap plan XML in the temp repo's .agents/plans/.
        planDir = tempDir.resolve(".agents/plans").toFile();
        planDir.mkdirs();
        xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");

        XmlService xmlService = new XmlService();
        List<XmlService.Task> tasks = List.of(
                new XmlService.Task(1, "Worker lifecycle test task", "low")
        );
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        // Bootstrap ledger.
        ledgerFile = tempDir.resolve(".agents/ledger.jsonl").toFile();
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Remove any leftover worktrees before the temp dir is cleaned.
        runGit("worktree", "prune");
    }

    /**
     * Happy path: claim -> worker-init -> write a file (simulating subagent) ->
     * worker-finish -> worker-cleanup.
     *
     * Asserts:
     * - Ledger contains AGENT_START, WORKTREE_CREATED, PATCH_EMITTED, COMMIT_RECORDED, CLEANUP in order.
     * - XML <commit> field is populated.
     * - Worktree directory is removed after cleanup.
     * - Branch agent-work/1 still exists after cleanup.
     */
    @Test
    void happyPath_workerLifecycleLeavesCommitAndBranch() throws Exception {
        CommandLine cli = new CommandLine(new TasksCli());
        int planNum = PLAN_NUM;
        String taskId = "1";

        // Run all commands from the temp repo root so git and XML paths resolve correctly.
        System.setProperty("user.dir", tempDir.toString()); // hint for commands that use Paths.get(".")

        int exit;

        // claim
        exit = cli.execute("claim", "--plan", String.valueOf(planNum), "--task", taskId);
        assertEquals(0, exit, "claim should exit 0");

        // worker-init — prints worktree path to stdout
        exit = cli.execute("worker-init", "--plan", String.valueOf(planNum), "--task", taskId);
        assertEquals(0, exit, "worker-init should exit 0");

        Path worktreePath = tempDir.resolve(".agents/tasks/" + taskId);
        assertTrue(worktreePath.toFile().isDirectory(), "worktree directory should exist after worker-init");

        // Simulate subagent editing files inside the worktree (no git operations).
        Files.writeString(worktreePath.resolve("output.txt"), "subagent output");

        // worker-finish — captures diff, commits, records events, updates XML
        exit = cli.execute("worker-finish", "--plan", String.valueOf(planNum), "--task", taskId);
        assertEquals(0, exit, "worker-finish should exit 0");

        // worker-cleanup — removes worktree dir, keeps branch
        exit = cli.execute("worker-cleanup", "--plan", String.valueOf(planNum), "--task", taskId);
        assertEquals(0, exit, "worker-cleanup should exit 0");

        // --- assertions ---

        // Ledger events in order
        LedgerService ledger = new LedgerService(tempDir);
        List<String> hashes = ledger.readHashes();
        List<Event> events = hashes.stream().map(h -> {
            try { return ledger.readEvent(h); } catch (IOException e) { throw new RuntimeException(e); }
        }).toList();

        List<EventType> types = events.stream().map(Event::eventType).toList();
        assertTrue(types.contains(EventType.AGENT_START), "ledger should have AGENT_START");
        assertTrue(types.contains(EventType.WORKTREE_CREATED), "ledger should have WORKTREE_CREATED");
        assertTrue(types.contains(EventType.PATCH_EMITTED), "ledger should have PATCH_EMITTED");
        assertTrue(types.contains(EventType.COMMIT_RECORDED), "ledger should have COMMIT_RECORDED");
        assertTrue(types.contains(EventType.CLEANUP), "ledger should have CLEANUP");

        // Order: AGENT_START before WORKTREE_CREATED before PATCH_EMITTED before CLEANUP
        int idxStart = types.indexOf(EventType.AGENT_START);
        int idxWt    = types.indexOf(EventType.WORKTREE_CREATED);
        int idxPatch = types.indexOf(EventType.PATCH_EMITTED);
        int idxClean = types.indexOf(EventType.CLEANUP);
        assertTrue(idxStart < idxWt && idxWt < idxPatch && idxPatch < idxClean, "events must be in lifecycle order");

        // XML commit field populated
        XmlService xmlService = new XmlService();
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        String commit = planTasks.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == 1)
                .findFirst().orElseThrow()
                .getCommit();
        assertNotNull(commit, "XML <commit> should be populated after worker-finish");
        assertFalse(commit.isBlank(), "XML <commit> should not be blank");

        // Worktree directory gone
        assertFalse(worktreePath.toFile().exists(), "worktree directory should be removed after worker-cleanup");

        // Branch agent-work/1 still exists
        Process p = new ProcessBuilder("git", "branch", "--list", "agent-work/" + taskId)
                .directory(tempDir.toFile()).start();
        String branchOutput = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        assertTrue(branchOutput.contains("agent-work/" + taskId),
                "branch agent-work/" + taskId + " should survive cleanup");
    }

    /**
     * Guard test: if the subagent commits inside the worktree, worker-finish must
     * exit non-zero and record no PATCH_EMITTED or COMMIT_RECORDED event.
     */
    @Test
    void workerFinish_abortsWhenSubagentCommitted() throws Exception {
        CommandLine cli = new CommandLine(new TasksCli());
        int planNum = PLAN_NUM;
        String taskId = "1";

        System.setProperty("user.dir", tempDir.toString());

        cli.execute("claim", "--plan", String.valueOf(planNum), "--task", taskId);
        cli.execute("worker-init", "--plan", String.valueOf(planNum), "--task", taskId);

        Path worktreePath = tempDir.resolve(".agents/tasks/" + taskId);

        // Simulate rogue subagent that commits.
        Files.writeString(worktreePath.resolve("rogue.txt"), "rogue");
        runGit("-C", worktreePath.toString(), "add", "rogue.txt");
        runGit("-C", worktreePath.toString(), "commit", "-q", "-m", "rogue commit");

        LedgerService ledger = new LedgerService(tempDir);
        int beforeCount = ledger.readHashes().size();

        int exit = cli.execute("worker-finish", "--plan", String.valueOf(planNum), "--task", taskId);
        assertNotEquals(0, exit, "worker-finish should exit non-zero when subagent committed");

        // No PATCH_EMITTED or COMMIT_RECORDED added
        int afterCount = ledger.readHashes().size();
        assertEquals(beforeCount, afterCount, "no ledger events should be added when guard fires");
    }

    // --- helpers ---

    private void git(String... args) throws IOException, InterruptedException {
        runGit(args);
    }

    private void runGit(String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IOException("git failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }
}
