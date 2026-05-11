package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.TasksCli;
import io.bitken.shipsmooth.tasks.integration.IntegrationLedger;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
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
 * Integration tests for IntegrateCommand resume and --force behaviour.
 *
 * These tests run against the real git repo (plugin-tasks-java/ is inside shipsmooth/).
 * Plan 992 is reserved for these tests.
 */
public class IntegrateCommandTest {

    private static final int PLAN_NUM = 992;
    private static final String INTEGRATION_BRANCH = "integration/plan-" + PLAN_NUM;
    private static final String INTEGRATION_REL = ".agents/integration/plan-" + PLAN_NUM;

    private final Path repoRoot = Paths.get(".");
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");

    @BeforeEach
    void setUp() throws Exception {
        planDir.mkdirs();
        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();
        cleanup();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    /**
     * When integration/plan-N already exists with task 2 merged (PATCH_INTEGRATED in ledger),
     * integrate should resume from task 3 without re-merging task 2 or failing.
     *
     * Setup:
     *   - Two agent-work branches: agent-work/2 (already merged into integration branch)
     *     and agent-work/3 (not yet merged).
     *   - COMMIT_RECORDED events for tasks 2 and 3.
     *   - PATCH_INTEGRATED event for task 2 only.
     *   - The integration worktree exists at .agents/integration/plan-992.
     *
     * Expected: integrate exits 0, task 3 is merged, a second PATCH_INTEGRATED event is
     * written for task 3, and the integration worktree is removed.
     */
    @Test
    void resumesFromLastPatchIntegratedWhenIntegrationBranchAlreadyExists() throws Exception {
        // Write plan markdown and XML with tasks 2 and 3 only (no task 1 complexity)
        Files.writeString(mdFile.toPath(),
                "### Task 2: Add file A [Low]\n\n" +
                "### Task 3: Add file B [Low]\n");
        XmlService xmlService = new XmlService();
        List<XmlService.Task> tasks = List.of(
                new XmlService.Task(2, "Add file A", "low"),
                new XmlService.Task(3, "Add file B", "low")
        );
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        // Create agent-work/2: adds fileA.txt
        createAgentWorkBranch("2", "fileA.txt", "content-A");
        // Create agent-work/3: adds fileB.txt (no conflict with fileA)
        createAgentWorkBranch("3", "fileB.txt", "content-B");

        // Record COMMIT_RECORDED for both tasks
        LedgerService ledger = new LedgerService(repoRoot);
        recordCommitEvent(ledger, "2", "agent-work/2");
        recordCommitEvent(ledger, "3", "agent-work/3");

        // Simulate prior session: create integration branch + worktree, merge task 2 into it,
        // record PATCH_INTEGRATED for task 2.
        String headSha = git(repoRoot.toFile(), "rev-parse", "HEAD").trim();
        git(repoRoot.toFile(), "worktree", "add", INTEGRATION_REL, "-b", INTEGRATION_BRANCH, headSha);
        File integrationDir = repoRoot.resolve(INTEGRATION_REL).toFile();
        git(integrationDir, "merge", "--squash", "agent-work/2");
        git(integrationDir, "commit", "-m", "task(2): Add file A");
        String task2Sha = git(integrationDir, "rev-parse", "HEAD").trim();
        String agentWork2Sha = git(repoRoot.toFile(), "rev-parse", "agent-work/2").trim();

        IntegrationLedger iLedger = new IntegrationLedger(ledger, PLAN_NUM);
        // Record INTEGRATION_PLAN to anchor the "prior session" start — resume logic
        // looks for PATCH_INTEGRATED events after the last INTEGRATION_PLAN.
        iLedger.recordIntegrationPlan(List.of(2, 3), INTEGRATION_BRANCH);
        iLedger.recordPatchIntegrated(2, task2Sha, agentWork2Sha);

        // At this point: integration branch exists with task 2, but not task 3.
        // Running integrate should resume from task 3.
        int exit = new CommandLine(new TasksCli()).execute(
                "integrate",
                "--plan", String.valueOf(PLAN_NUM),
                "--task-branch", currentBranch(),
                "--verify-cmd", "echo ok"
        );

        assertEquals(0, exit, "integrate should exit 0 on resume");

        // PATCH_INTEGRATED for task 3 must appear in ledger
        Event task3Integrated = ledger.findLastEvent("3", EventType.PATCH_INTEGRATED);
        assertNotNull(task3Integrated, "ledger should have PATCH_INTEGRATED for task 3");

        // Integration worktree should be gone
        assertFalse(repoRoot.resolve(INTEGRATION_REL).toFile().isDirectory(),
                "integration worktree should be cleaned up");

        // fileB.txt and fileA.txt should be present in integration/plan-992 branch tip.
        // Use --full-tree -r to list all files regardless of cwd (tests run from plugin-tasks-java/).
        String integrationTip = git(repoRoot.toFile(), "rev-parse", INTEGRATION_BRANCH).trim();
        String tree = git(repoRoot.toFile(), "ls-tree", "--full-tree", "-r", "--name-only", integrationTip);
        assertTrue(tree.contains("fileB.txt"), "integration branch tip should contain fileB.txt");
        assertTrue(tree.contains("fileA.txt"), "integration branch tip should still contain fileA.txt");
    }

    /**
     * When --force is passed, integrate should delete the existing integration branch and
     * worktree and start fresh, re-merging all tasks from the beginning.
     */
    @Test
    void forceFlag_deletesExistingIntegrationAndStartsFresh() throws Exception {
        Files.writeString(mdFile.toPath(), "### Task 2: Add file A [Low]\n");
        XmlService xmlService = new XmlService();
        List<XmlService.Task> tasks = List.of(new XmlService.Task(2, "Add file A", "low"));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        createAgentWorkBranch("2", "fileA.txt", "content-A");
        LedgerService ledger = new LedgerService(repoRoot);
        recordCommitEvent(ledger, "2", "agent-work/2");

        // Create a stale integration worktree (empty, as if a prior run died at startup)
        String headSha = git(repoRoot.toFile(), "rev-parse", "HEAD").trim();
        git(repoRoot.toFile(), "worktree", "add", INTEGRATION_REL, "-b", INTEGRATION_BRANCH, headSha);

        // Without --force this would fail; with --force it should succeed
        int exit = new CommandLine(new TasksCli()).execute(
                "integrate",
                "--plan", String.valueOf(PLAN_NUM),
                "--task-branch", currentBranch(),
                "--verify-cmd", "echo ok",
                "--force"
        );

        assertEquals(0, exit, "integrate --force should exit 0 even when integration branch already exists");

        Event task2Integrated = ledger.findLastEvent("2", EventType.PATCH_INTEGRATED);
        assertNotNull(task2Integrated, "PATCH_INTEGRATED for task 2 should appear after --force run");
    }

    // --- helpers ---

    private void createAgentWorkBranch(String taskId, String fileName, String content)
            throws IOException, InterruptedException {
        String branchName = "agent-work/" + taskId;
        String headSha = git(repoRoot.toFile(), "rev-parse", "HEAD").trim();
        // Create branch at current HEAD
        git(repoRoot.toFile(), "branch", branchName, headSha);
        // Worktree to write file
        String wtRel = ".agents/tmp/test-worker-" + taskId;
        git(repoRoot.toFile(), "worktree", "add", wtRel, branchName);
        File wtDir = repoRoot.resolve(wtRel).toFile();
        Files.writeString(repoRoot.resolve(wtRel).resolve(fileName), content);
        git(wtDir, "add", fileName);
        git(wtDir, "commit", "-m", "task(" + taskId + "): add " + fileName);
        git(repoRoot.toFile(), "worktree", "remove", "--force", wtRel);
    }

    private void recordCommitEvent(LedgerService ledger, String taskId, String branch)
            throws IOException, InterruptedException {
        String sha = git(repoRoot.toFile(), "rev-parse", branch).trim();
        ledger.record(Event.forTask(
                EventType.COMMIT_RECORDED, taskId, null, sha,
                java.util.Map.of("branch", branch, "integration_mode", "worktree")
        ));
    }

    private String currentBranch() throws IOException, InterruptedException {
        return git(repoRoot.toFile(), "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    private void cleanup() throws Exception {
        // Remove integration worktree
        Path wtPath = repoRoot.resolve(INTEGRATION_REL);
        if (wtPath.toFile().isDirectory()) {
            try { git(repoRoot.toFile(), "worktree", "remove", "--force", INTEGRATION_REL); }
            catch (Exception ignored) {}
        }
        // Delete integration branch
        deleteGitBranch(INTEGRATION_BRANCH);
        // Delete agent-work branches used in tests
        deleteGitBranch("agent-work/2");
        deleteGitBranch("agent-work/3");
        // Delete temp worktrees
        for (String id : List.of("2", "3")) {
            String wtRel = ".agents/tmp/test-worker-" + id;
            Path wt = repoRoot.resolve(wtRel);
            if (wt.toFile().isDirectory()) {
                try { git(repoRoot.toFile(), "worktree", "remove", "--force", wtRel); }
                catch (Exception ignored) {}
            }
        }
        // Clean up plan files
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

    /** Runs git with the given sub-args, returns stdout. Throws on non-zero exit. */
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