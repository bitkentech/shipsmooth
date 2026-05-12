package io.bitken.shipsmooth.tasks.workflow;

import io.bitken.shipsmooth.tasks.integration.IntegrationLedger;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
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
 * Service-level test for {@link WorkflowService#runIntegration}.
 *
 * <p>Plan-37 task 4 (de-risk pass): exercise the resume-from-PATCH_INTEGRATED
 * branch of integrate through the service, mirroring {@code IntegrateCommandTest}'s
 * {@code resumesFromLastPatchIntegratedWhenIntegrationBranchAlreadyExists}.
 *
 * <p>Plan 996 is reserved for this test.
 */
class WorkflowServiceRunIntegrationTest {

    private static final int PLAN_NUM = 996;
    private static final String INTEGRATION_BRANCH = "integration/plan-" + PLAN_NUM;
    private static final String INTEGRATION_REL = ".agents/integration/plan-" + PLAN_NUM;

    private final Path repoRoot = Paths.get(".");
    private final WorkflowService service = new WorkflowServiceImpl();
    private File xmlFile;
    private File mdFile;

    @BeforeEach
    void setUp() throws Exception {
        File planDir = new File(".agents/plans");
        planDir.mkdirs();
        xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
        mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
        new LedgerService(repoRoot).ensureLedgerFile();
        cleanup();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanup();
    }

    /**
     * Resume from a prior integration: tasks 2 and 3, with task 2 already
     * integrated. runIntegration must merge task 3 only and return success.
     */
    @Test
    void runIntegration_resumesFromLastPatchIntegrated() throws Exception {
        Files.writeString(mdFile.toPath(),
                "### Task 2: Add file A [Low]\n\n" +
                "### Task 3: Add file B [Low]\n");
        XmlService xmlService = new XmlService();
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1",
                List.of(new XmlService.Task(2, "Add file A", "low"),
                        new XmlService.Task(3, "Add file B", "low")));
        xmlService.writePlanTasks(planTasks, xmlFile);

        createAgentWorkBranch("2", "fileA-996.txt", "content-A");
        createAgentWorkBranch("3", "fileB-996.txt", "content-B");

        LedgerService ledger = new LedgerService(repoRoot);
        recordCommitEvent(ledger, "2", "agent-work/2");
        recordCommitEvent(ledger, "3", "agent-work/3");

        String headSha = git(repoRoot.toFile(), "rev-parse", "HEAD").trim();
        git(repoRoot.toFile(), "worktree", "add", INTEGRATION_REL, "-b", INTEGRATION_BRANCH, headSha);
        File integrationDir = repoRoot.resolve(INTEGRATION_REL).toFile();
        git(integrationDir, "merge", "--squash", "agent-work/2");
        git(integrationDir, "commit", "-m", "task(2): Add file A");
        String task2Sha = git(integrationDir, "rev-parse", "HEAD").trim();
        String agentWork2Sha = git(repoRoot.toFile(), "rev-parse", "agent-work/2").trim();

        IntegrationLedger iLedger = new IntegrationLedger(ledger, PLAN_NUM);
        iLedger.recordIntegrationPlan(List.of(2, 3), INTEGRATION_BRANCH);
        iLedger.recordPatchIntegrated(2, task2Sha, agentWork2Sha);

        IntegrationOptions opts = new IntegrationOptions()
                .taskBranch(currentBranch())
                .verifyCmd("echo ok");

        IntegrationResult result = service.runIntegration(PLAN_NUM, opts);

        assertTrue(result.success(), "runIntegration must report success");
        assertNotNull(result.integrationTipSha(), "tip SHA must be returned on success");
        assertNotNull(result.fastForwardCommand(), "fast-forward command must be returned on success");
        assertTrue(result.fastForwardCommand().contains(INTEGRATION_BRANCH),
                "fast-forward command must reference integration branch");

        Event task3Integrated = ledger.findLastEvent("3", EventType.PATCH_INTEGRATED);
        assertNotNull(task3Integrated, "ledger should have PATCH_INTEGRATED for task 3");

        assertFalse(repoRoot.resolve(INTEGRATION_REL).toFile().isDirectory(),
                "integration worktree should be cleaned up");
    }

    /**
     * No COMMIT_RECORDED events for the plan: runIntegration must throw
     * a typed WorkflowException, not silently return failed.
     */
    @Test
    void runIntegration_throwsWhenNothingToIntegrate() throws Exception {
        Files.writeString(mdFile.toPath(), "### Task 9: Empty plan [Low]\n");
        XmlService xmlService = new XmlService();
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1",
                List.of(new XmlService.Task(9, "Empty plan", "low")));
        xmlService.writePlanTasks(planTasks, xmlFile);

        IntegrationOptions opts = new IntegrationOptions().verifyCmd("echo ok");

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> service.runIntegration(PLAN_NUM, opts));
        assertEquals(WorkflowErrorCode.NOTHING_TO_INTEGRATE, ex.errorCode());
    }

    // -------- helpers (mirror IntegrateCommandTest) --------

    private void createAgentWorkBranch(String taskId, String fileName, String content)
            throws IOException, InterruptedException {
        String branchName = "agent-work/" + taskId;
        String headSha = git(repoRoot.toFile(), "rev-parse", "HEAD").trim();
        git(repoRoot.toFile(), "branch", branchName, headSha);
        String wtRel = ".agents/tmp/test-worker-" + taskId + "-996";
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
        ledger.record(Event.forTask(EventType.COMMIT_RECORDED, taskId, null, sha,
                java.util.Map.of("branch", branch, "integration_mode", "worktree")));
    }

    private String currentBranch() throws IOException, InterruptedException {
        return git(repoRoot.toFile(), "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    private void cleanup() throws Exception {
        Path wtPath = repoRoot.resolve(INTEGRATION_REL);
        if (wtPath.toFile().isDirectory()) {
            try { git(repoRoot.toFile(), "worktree", "remove", "--force", INTEGRATION_REL); }
            catch (Exception ignored) {}
        }
        deleteGitBranch(INTEGRATION_BRANCH);
        for (String id : List.of("2", "3")) {
            deleteGitBranch("agent-work/" + id);
            String wtRel = ".agents/tmp/test-worker-" + id + "-996";
            Path wt = repoRoot.resolve(wtRel);
            if (wt.toFile().isDirectory()) {
                try { git(repoRoot.toFile(), "worktree", "remove", "--force", wtRel); }
                catch (Exception ignored) {}
            }
        }
        if (xmlFile != null) xmlFile.delete();
        if (mdFile != null) mdFile.delete();
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