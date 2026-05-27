package io.bitken.ss.workflow;
import io.bitken.ss.ShipsmoothDataLocator;

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
 * Service-level tests for {@link WorkflowService#finalizeWorker}.
 *
 * <p>Plan-37 task 3 (de-risk pass): exercise the same observable behaviour
 * as {@code worker-finish}, driven through the service.
 *
 * <p>Plan 995 is reserved for this test class.
 */
class WorkflowServiceFinalizeWorkerTest {

    private static final int PLAN_NUM = 995;
    private static final String TASK_ID = "1";
    private static final String WORKTREE_REL = ".agents/tasks/" + TASK_ID;
    private static final String BRANCH = "agent-work/" + TASK_ID;

    private final Path repoRoot = Paths.get(".");
    private final WorkflowService service = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(repoRoot))
            .build()
            .workflowService();

    private File planDir;
    private File xmlFile;
    private File mdFile;

    @BeforeEach
    void setUp() throws Exception {
        planDir = new File(".agents/plans");
        xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
        mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
        planDir.mkdirs();
        new EventLedger(repoRoot).ensureLedgerFile();
        cleanup();

        Files.writeString(mdFile.toPath(), "### Task 1: Finalize test [Low]\n");
        TaskStore xs = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Finalize test", "low"));
        PlanTasks planTasks = xs.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xs.writePlanTasks(planTasks, xmlFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    /**
     * Happy path: initializeWorker, simulated subagent edit, finalizeWorker
     * emits PATCH_EMITTED + COMMIT_RECORDED and advances the agent-work branch.
     */
    @Test
    void finalizeWorker_emitsPatchAndCommitEvents() throws Exception {
        EventLedger ledger = new EventLedger(repoRoot);
        int snapshot = ledger.readHashes().size() - 1;

        service.initializeWorker(PLAN_NUM, TASK_ID, null);

        File worktreeDir = repoRoot.resolve(WORKTREE_REL).toFile();
        Files.writeString(worktreeDir.toPath().resolve("plan37-task3.txt"), "task 3 de-risk\n");

        service.finalizeWorker(PLAN_NUM, TASK_ID);

        Event patch = ledger.findLastEventAfter(TASK_ID, EventType.PATCH_EMITTED, snapshot);
        Event commit = ledger.findLastEventAfter(TASK_ID, EventType.COMMIT_RECORDED, snapshot);
        assertNotNull(patch, "PATCH_EMITTED must be written");
        assertNotNull(commit, "COMMIT_RECORDED must be written");

        // Branch tip must differ from base SHA recorded at init.
        Event wt = ledger.findLastEventAfter(TASK_ID, EventType.WORKTREE_CREATED, snapshot);
        String tip = git(repoRoot.toFile(), "rev-parse", BRANCH).trim();
        assertNotEquals(wt.baseCommitSha(), tip,
                "agent-work branch must advance past base SHA after finalizeWorker");
    }

    /**
     * Contract violation: subagent commits in worktree. finalizeWorker must
     * fail with a typed exception and write no PATCH_EMITTED/COMMIT_RECORDED.
     */
    @Test
    void finalizeWorker_rejectsSubagentCommits() throws Exception {
        EventLedger ledger = new EventLedger(repoRoot);
        int snapshot = ledger.readHashes().size() - 1;

        service.initializeWorker(PLAN_NUM, TASK_ID, null);

        File worktreeDir = repoRoot.resolve(WORKTREE_REL).toFile();
        Files.writeString(worktreeDir.toPath().resolve("rogue.txt"), "x\n");
        git(worktreeDir, "add", "rogue.txt");
        git(worktreeDir, "-c", "user.email=t@example.com",
                "-c", "user.name=Test", "commit", "-m", "rogue");

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> service.finalizeWorker(PLAN_NUM, TASK_ID));
        assertNotEquals(0, ex.exitCode());

        assertNull(ledger.findLastEventAfter(TASK_ID, EventType.PATCH_EMITTED, snapshot),
                "PATCH_EMITTED must not be written when invariant is violated");
        assertNull(ledger.findLastEventAfter(TASK_ID, EventType.COMMIT_RECORDED, snapshot),
                "COMMIT_RECORDED must not be written when invariant is violated");
    }

    /**
     * Empty diff is a contract violation: subagent did nothing. finalizeWorker
     * must reject and write no commit/patch events.
     */
    @Test
    void finalizeWorker_rejectsEmptyDiff() throws Exception {
        EventLedger ledger = new EventLedger(repoRoot);
        int snapshot = ledger.readHashes().size() - 1;

        service.initializeWorker(PLAN_NUM, TASK_ID, null);
        // No file write — diff is empty.

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> service.finalizeWorker(PLAN_NUM, TASK_ID));
        assertNotEquals(0, ex.exitCode());

        assertNull(ledger.findLastEventAfter(TASK_ID, EventType.PATCH_EMITTED, snapshot));
        assertNull(ledger.findLastEventAfter(TASK_ID, EventType.COMMIT_RECORDED, snapshot));
    }

    // -------- helpers --------

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