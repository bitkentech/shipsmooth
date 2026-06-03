package io.bitken.ss.cli;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
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
 * Phase-2 integration preamble for plan-37 (service layer).
 *
 * Exercises the worker lifecycle end-to-end: worker-init creates a worktree and writes
 * WORKTREE_CREATED, an external edit simulates subagent work, worker-finish captures the
 * diff and writes PATCH_EMITTED + COMMIT_RECORDED.
 *
 * After plan-37 lands, the same observable behaviour must hold even though orchestration
 * has moved into WorkflowService. If these tests fail post-migration, the refactor is not
 * behaviourally equivalent.
 *
 * Plan 993 is reserved for this test.
 */
public class WorkerLifecycleIntegrationTest {

    private static final int PLAN_NUM = 993;
    private static final String TASK_ID = "1";
    private static final String WORKTREE_REL = ".agents/tasks/" + TASK_ID;
    private static final String BRANCH = "agent-work/" + TASK_ID;

    private final Path repoRoot = Paths.get(".");
    private final AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(repoRoot, new ExperimentalMode(true)))
            .build();

    /** One-shot CLI bound to these args, mirroring main(). */
    private int run(String... args) {
        return new Shipsmooth(app, args).execute();
    }

    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");

    @BeforeEach
    void setUp() throws Exception {
        planDir.mkdirs();
        new EventLedger(repoRoot).ensureLedgerFile();
        cleanup();

        // Plan markdown + XML so worker-finish can resolve the task name.
        Files.writeString(mdFile.toPath(), "### Task 1: Worker lifecycle smoke [Low]\n");
        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        List<TaskStore.Task> tasks = List.of(
                new TaskStore.Task(1, "Worker lifecycle smoke", "low")
        );
        PlanTasks planTasks = xmlService.generatePlanTasks(
                PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    /**
     * Happy path: worker-init -> external file edit -> worker-finish.
     *
     * Asserts the full event sequence (WORKTREE_CREATED, PATCH_EMITTED, COMMIT_RECORDED)
     * and that the agent-work branch tip differs from the base SHA recorded at init.
     */
    @Test
    void workerLifecycle_emitsExpectedLedgerEvents() throws Exception {
        EventLedger ledger = new EventLedger(repoRoot);
        long ledgerCountBefore = countEvents(ledger);
        int snapshotIndex = (int) ledgerCountBefore - 1;

        // 1. worker-init creates worktree + WORKTREE_CREATED event.
        int initExit = run(
                "--enable-experimental", "worker", "init","--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, initExit, "worker-init must exit 0");

        File worktreeDir = repoRoot.resolve(WORKTREE_REL).toFile();
        assertTrue(worktreeDir.isDirectory(), "worktree dir must exist after worker-init");

        Event wtCreated = ledger.findLastEventAfter(TASK_ID, EventType.WORKTREE_CREATED, snapshotIndex);
        assertNotNull(wtCreated, "WORKTREE_CREATED event must be written by this test run");
        String baseSha = wtCreated.baseCommitSha();
        assertNotNull(baseSha, "WORKTREE_CREATED event must record a base SHA");
        assertFalse(baseSha.isBlank(), "base SHA must not be blank");

        // 2. Simulate subagent writing a file (no git commands — that is the contract).
        Path created = worktreeDir.toPath().resolve("plan37-smoke.txt");
        Files.writeString(created, "service-layer preamble\n");

        // 3. worker-finish captures diff, commits on the agent-work branch.
        int finishExit = run(
                "--enable-experimental", "worker", "finish","--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, finishExit, "worker-finish must exit 0 on happy path");

        // 4. Ledger must contain PATCH_EMITTED and COMMIT_RECORDED written *during this test*.
        Event patch = ledger.findLastEventAfter(TASK_ID, EventType.PATCH_EMITTED, snapshotIndex);
        Event commit = ledger.findLastEventAfter(TASK_ID, EventType.COMMIT_RECORDED, snapshotIndex);
        assertNotNull(patch, "PATCH_EMITTED must be written by worker-finish");
        assertNotNull(commit, "COMMIT_RECORDED must be written by worker-finish");

        // 5. The agent-work branch tip must differ from the recorded base SHA.
        String tip = git(repoRoot.toFile(), "rev-parse", BRANCH).trim();
        assertNotEquals(baseSha, tip,
                "agent-work branch tip must advance past worktree-creation base SHA");

        // 6. At least three new events landed since the test started.
        long ledgerCountAfter = countEvents(ledger);
        assertTrue(ledgerCountAfter - ledgerCountBefore >= 3,
                "expected at least 3 new ledger events (WORKTREE_CREATED, PATCH_EMITTED, COMMIT_RECORDED)");
    }

    /**
     * Contract violation: subagent commits inside the worktree. worker-finish must abort
     * loudly and not write PATCH_EMITTED or COMMIT_RECORDED.
     *
     * Counts events written during this test only, since the production ledger
     * (.agents/ledger.jsonl) is shared across test methods and may already carry
     * PATCH_EMITTED / COMMIT_RECORDED events from earlier tests.
     */
    @Test
    void workerFinish_abortsWhenSubagentCommittedInWorktree() throws Exception {
        EventLedger ledger = new EventLedger(repoRoot);
        int snapshotIndex = ledger.readHashes().size() - 1; // -1 == "from the beginning is OK; we only care about after this"

        int initExit = run(
                "--enable-experimental", "worker", "init","--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertEquals(0, initExit);

        File worktreeDir = repoRoot.resolve(WORKTREE_REL).toFile();
        Files.writeString(worktreeDir.toPath().resolve("rogue.txt"), "x\n");

        // Simulate a misbehaving subagent that ran git commit (forbidden).
        git(worktreeDir, "add", "rogue.txt");
        git(worktreeDir, "-c", "user.email=test@example.com",
                "-c", "user.name=Test", "commit", "-m", "rogue commit");

        int finishExit = run(
                "--enable-experimental", "worker", "finish","--plan", String.valueOf(PLAN_NUM), "--task", TASK_ID);
        assertNotEquals(0, finishExit, "worker-finish must reject worktree with subagent commits");

        Event newPatch = ledger.findLastEventAfter(TASK_ID, EventType.PATCH_EMITTED, snapshotIndex);
        Event newCommit = ledger.findLastEventAfter(TASK_ID, EventType.COMMIT_RECORDED, snapshotIndex);
        assertNull(newPatch, "PATCH_EMITTED must NOT be written when invariant is violated");
        assertNull(newCommit, "COMMIT_RECORDED must NOT be written when invariant is violated");
    }

    // -------- helpers --------

    private long countEvents(EventLedger ledger) throws Exception {
        return ledger.readHashes().size();
    }

    private void cleanup() throws Exception {
        Path wt = repoRoot.resolve(WORKTREE_REL);
        if (wt.toFile().isDirectory()) {
            try { git(repoRoot.toFile(), "worktree", "remove", "--force", WORKTREE_REL); }
            catch (Exception ignored) {}
        }
        deleteGitBranch(BRANCH);
        xmlFile.delete();
        mdFile.delete();
    }

    private void deleteGitBranch(String branch) {
        try {
            Process check = new ProcessBuilder("git", "rev-parse", "--verify", branch)
                    .directory(repoRoot.toFile()).redirectErrorStream(true).start();
            check.getInputStream().readAllBytes();
            check.waitFor(10, TimeUnit.SECONDS);
            if (check.exitValue() == 0) {
                git(repoRoot.toFile(), "branch", "-D", branch);
            }
        } catch (Exception ignored) {}
    }

    private String git(File cwd, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("timeout"); }
        if (p.exitValue() != 0) throw new IOException("git " + String.join(" ", args) + " failed\n" + err);
        return out;
    }
}
