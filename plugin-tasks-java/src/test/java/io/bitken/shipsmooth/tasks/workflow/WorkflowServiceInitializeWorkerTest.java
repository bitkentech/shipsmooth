package io.bitken.shipsmooth.tasks.workflow;

import io.bitken.shipsmooth.tasks.di.DaggerAppComponents;
import io.bitken.shipsmooth.tasks.di.ServicesModule;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service-level tests for {@link WorkflowService#initializeWorker}.
 *
 * <p>Plan-37 task 2 (de-risk pass): exercise the same observable behaviour as
 * {@code worker-init}, but driven through the service rather than the
 * PicoCLI command.
 *
 * <p>Plan 994 is reserved for this test class.
 */
class WorkflowServiceInitializeWorkerTest {

    private static final int PLAN_NUM = 994;
    private static final String TASK_ID = "1";
    private static final String WORKTREE_REL = ".agents/tasks/" + TASK_ID;
    private static final String BRANCH = "agent-work/" + TASK_ID;

    private final Path repoRoot = Paths.get(".");
    private final WorkflowService service = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(repoRoot))
            .build()
            .workflowService();

    @BeforeEach
    void setUp() throws Exception {
        new LedgerService(repoRoot).ensureLedgerFile();
        cleanup();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    /**
     * Happy path: initializeWorker creates the worktree, the branch, and writes a
     * WORKTREE_CREATED event referencing the recorded base SHA.
     */
    @Test
    void initializeWorker_createsWorktreeBranchAndLedgerEvent() throws Exception {
        LedgerService ledger = new LedgerService(repoRoot);
        int snapshot = ledger.readHashes().size() - 1;

        service.initializeWorker(PLAN_NUM, TASK_ID, null);

        assertTrue(repoRoot.resolve(WORKTREE_REL).toFile().isDirectory(),
                "worktree directory must exist after initializeWorker");
        // Branch must exist.
        String tip = git(repoRoot.toFile(), "rev-parse", BRANCH).trim();
        assertFalse(tip.isBlank(), "agent-work branch must exist");

        Event wt = ledger.findLastEventAfter(TASK_ID, EventType.WORKTREE_CREATED, snapshot);
        assertNotNull(wt, "WORKTREE_CREATED must be written");
        assertNotNull(wt.baseCommitSha(), "WORKTREE_CREATED must record base SHA");
        assertFalse(wt.baseCommitSha().isBlank(), "base SHA must not be blank");
    }

    /**
     * Calling initializeWorker when the worktree already exists must fail with
     * a typed WorkflowException, not silently overwrite or leak a raw error.
     */
    @Test
    void initializeWorker_failsLoudlyWhenWorktreeAlreadyExists() throws Exception {
        service.initializeWorker(PLAN_NUM, TASK_ID, null);

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> service.initializeWorker(PLAN_NUM, TASK_ID, null));
        assertNotNull(ex.errorCode(), "WorkflowException must carry an error code");
        assertNotEquals(0, ex.exitCode(), "exit code must be non-zero on failure");
    }

    /**
     * When --base is supplied, the recorded WORKTREE_CREATED event must reference
     * that SHA (not HEAD).
     */
    @Test
    void initializeWorker_recordsExplicitBaseShaWhenProvided() throws Exception {
        String headSha = git(repoRoot.toFile(), "rev-parse", "HEAD").trim();
        LedgerService ledger = new LedgerService(repoRoot);
        int snapshot = ledger.readHashes().size() - 1;

        service.initializeWorker(PLAN_NUM, TASK_ID, headSha);

        Event wt = ledger.findLastEventAfter(TASK_ID, EventType.WORKTREE_CREATED, snapshot);
        assertNotNull(wt);
        assertEquals(headSha, wt.baseCommitSha(),
                "explicit --base value must be recorded as base SHA");
    }

    // -------- helpers --------

    private void cleanup() throws Exception {
        Path wt = repoRoot.resolve(WORKTREE_REL);
        if (wt.toFile().isDirectory()) {
            try { git(repoRoot.toFile(), "worktree", "remove", "--force", WORKTREE_REL); }
            catch (Exception ignored) {}
        }
        deleteGitBranch(BRANCH);
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
