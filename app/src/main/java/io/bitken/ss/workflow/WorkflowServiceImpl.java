package io.bitken.ss.workflow;

import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.git.MergeResult;
import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.workflow.integration.IntegrationDefaults;
import io.bitken.ss.workflow.integration.IntegrationLedger;
import io.bitken.ss.workflow.integration.IntegrationOrder;
import io.bitken.ss.workflow.integration.Resolver;
import io.bitken.ss.workflow.integration.ResolverContext;
import io.bitken.ss.workflow.integration.LedgerSubagentRunner;
import io.bitken.ss.workflow.integration.SubagentResolver;
import io.bitken.ss.workflow.integration.TaskOrderInput;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.jaxb.TaskType;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.gw.TaskStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default {@link WorkflowService} implementation.
 *
 * <p>Collaborators ({@link WorktreeService}, {@link EventLedger}, {@link TaskStore},
 * {@link ProcessRunner}, {@link ProgressReporter}) are injected — the DI container
 * already provisions them and using the injected singletons (rather than fresh
 * instances) keeps the {@code WorktreeService} git semaphore meaningful across
 * concurrent calls.
 */
public class WorkflowServiceImpl implements WorkflowService {

    private final Path repoRoot;
    private final ShipsmoothDataLocator locator;
    private final ProcessRunner processes;
    private final WorktreeService git;
    private final EventLedger ledger;
    private final TaskStore xmlService;
    private final ProgressReporter reporter;

    public WorkflowServiceImpl(Path repoRoot, ProcessRunner processes,
                               WorktreeService git, EventLedger ledger,
                               TaskStore xmlService, ProgressReporter reporter) {
        this(repoRoot, new ShipsmoothDataLocator(repoRoot), processes, git, ledger, xmlService, reporter);
    }

    public WorkflowServiceImpl(Path repoRoot, ShipsmoothDataLocator locator, ProcessRunner processes,
                               WorktreeService git, EventLedger ledger,
                               TaskStore xmlService, ProgressReporter reporter) {
        this.repoRoot = repoRoot;
        this.locator = locator;
        this.processes = processes;
        this.git = git;
        this.ledger = ledger;
        this.xmlService = xmlService;
        this.reporter = reporter;
    }

    @Override
    public Path initializeWorker(int planId, String taskId, String baseSha) throws WorkflowException {
        try {
            String worktreeRel = locator.worktreeRel(taskId);
            String branch = locator.agentBranch(taskId);

            if (git.worktreeExists(worktreeRel)) {
                throw new WorkflowException(WorkflowErrorCode.WORKTREE_ALREADY_EXISTS,
                        "Error: worktree already exists at " + worktreeRel);
            }

            git.addWorktree(worktreeRel, branch, baseSha);

            String resolvedBase = (baseSha != null && !baseSha.isBlank()) ? baseSha : git.headSha();

            ledger.ensureLedgerFile();
            ledger.record(Event.forTask(EventType.WORKTREE_CREATED, taskId, resolvedBase, worktreeRel,
                    Map.of("branch", branch, "worktree_rel", worktreeRel)));

            return repoRoot.resolve(worktreeRel).toAbsolutePath();
        } catch (WorkflowException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowException(WorkflowErrorCode.INTERNAL_ERROR,
                    "initializeWorker failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void finalizeWorker(int planId, String taskId) throws WorkflowException {
        try {
            ledger.ensureLedgerFile();

            String worktreeRel = locator.worktreeRel(taskId);
            String branch = locator.agentBranch(taskId);
            File worktreeDir = repoRoot.resolve(worktreeRel).toFile();

            if (!worktreeDir.isDirectory()) {
                throw new WorkflowException(WorkflowErrorCode.WORKTREE_MISSING,
                        "Error: worktree directory not found: " + worktreeRel);
            }

            // The branch SHA must still equal the base SHA recorded at init —
            // if the subagent ran git commit (forbidden), they will differ.
            Event wtEvent = ledger.findLastEvent(taskId, EventType.WORKTREE_CREATED);
            String preCommitBranchSha = null;
            if (wtEvent != null) {
                String baseSha = wtEvent.baseCommitSha();
                if (baseSha != null && !baseSha.isBlank()) {
                    String currentBranchSha = git.branchSha(branch);
                    preCommitBranchSha = currentBranchSha;
                    if (!baseSha.equals(currentBranchSha)) {
                        String logOutput = git.logOneline(worktreeDir, baseSha + ".." + currentBranchSha);
                        throw new WorkflowException(WorkflowErrorCode.SUBAGENT_COMMITTED_IN_WORKTREE,
                                "worker-finish: subagent for task " + taskId
                                        + " created commits in the worktree.\n"
                                        + "This violates the contract: subagents must not run git.\n"
                                        + "Recorded commits:\n" + logOutput.trim() + "\n"
                                        + "Aborting; no PATCH_EMITTED or COMMIT_RECORDED event written.");
                    }
                }
            }

            String diff = git.diff(worktreeDir);
            if (diff.isBlank()) {
                throw new WorkflowException(WorkflowErrorCode.EMPTY_DIFF,
                        "worker-finish: subagent produced no changes (empty diff). Aborting.");
            }

            File xmlFile = locator.planTasksFile(planId);
            PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
            final String resolvedPreCommitBranchSha = preCommitBranchSha;
            String taskName = xmlService.getTaskName(planTasks, Integer.parseInt(taskId));

            String headSha = git.headSha();

            // resetHard inverse only fires if a ledger or XML write fails after commitAll
            // succeeds — keeping the branch tip and the ledger consistent.
            Transaction tx = new Transaction(reporter::warn);
            String commitSha;
            try {
                commitSha = git.commitAll(worktreeDir, "agent: task " + taskId + " - " + taskName);
                if (resolvedPreCommitBranchSha != null) {
                    tx.register("rollback worktree branch to " + resolvedPreCommitBranchSha,
                            () -> git.resetHard(worktreeDir, resolvedPreCommitBranchSha));
                }

                ledger.record(Event.forTask(EventType.PATCH_EMITTED, taskId, headSha, diff, Map.of()));
                ledger.record(Event.forTask(EventType.COMMIT_RECORDED, taskId, headSha, commitSha,
                        Map.of("branch", branch, "commit_sha", commitSha)));

                xmlService.setCommit(planTasks, Integer.parseInt(taskId), commitSha);
                xmlService.writePlanTasks(planTasks, xmlFile);

                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }

            reporter.info("worker-finish: task " + taskId + " committed on " + branch + " at " + commitSha);
        } catch (WorkflowException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowException(WorkflowErrorCode.INTERNAL_ERROR,
                    "finalizeWorker failed: " + e.getMessage(), e);
        }
    }

    @Override
    public IntegrationResult runIntegration(int planId, IntegrationOptions options) throws WorkflowException {
        try {
            return runIntegrationInternal(planId, options);
        } catch (WorkflowException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowException(WorkflowErrorCode.INTERNAL_ERROR,
                    "runIntegration failed: " + e.getMessage(), e);
        }
    }

    private IntegrationResult runIntegrationInternal(int plan, IntegrationOptions options) throws Exception {
        IntegrationContext ctx = buildContext(plan, options);

        TaskMerges merges = resolveTaskMerges(ctx);
        if (merges.orderInputs.isEmpty()) {
            throw new WorkflowException(WorkflowErrorCode.NOTHING_TO_INTEGRATE,
                    "integrate: no COMMIT_RECORDED events found for plan " + plan + ". Nothing to integrate.");
        }
        if (merges.taskAgentBranch.isEmpty()) {
            reporter.info("integrate: all tasks were done directly on task branch — nothing to merge.");
            return IntegrationResult.ok(null, null);
        }

        List<Integer> order = computeOrder(merges.orderInputs);

        WorktreePrep prep = prepareIntegrationWorktree(ctx, order, options.force());

        boolean completed = mergeTaskLoop(ctx, order, merges.taskAgentBranch, prep);
        if (!completed) {
            return IntegrationResult.failed();
        }

        return finalizeIntegration(ctx, order, merges.taskAgentBranch);
    }

    private IntegrationContext buildContext(int plan, IntegrationOptions options) throws Exception {
        ledger.ensureLedgerFile();
        IntegrationLedger iLedger = new IntegrationLedger(ledger, plan);

        String taskBranch = options.taskBranch();
        if (taskBranch == null || taskBranch.isBlank()) {
            taskBranch = git.currentBranch();
        }

        String verifyCmd = (options.verifyCmd() != null && !options.verifyCmd().isBlank())
                ? options.verifyCmd() : IntegrationDefaults.VERIFY_CMD;

        File xmlFile = locator.planTasksFile(plan);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);

        String integrationBranch = locator.integrationBranch(plan);
        String integrationRel = locator.integrationRel(plan);
        String integrationAbs = repoRoot.resolve(integrationRel).toAbsolutePath().toString();
        File integrationDir = repoRoot.resolve(integrationRel).toFile();

        return new IntegrationContext(
                plan, taskBranch, verifyCmd,
                options.maxLlmIterations(), options.maxTotalFailures(), options.resolverFactory(),
                iLedger, xmlFile, planTasks,
                integrationBranch, integrationRel, integrationAbs, integrationDir);
    }

    private TaskMerges resolveTaskMerges(IntegrationContext ctx) throws IOException, InterruptedException {
        Map<Integer, String> taskAgentBranch = new HashMap<>();
        List<TaskOrderInput> orderInputs = new ArrayList<>();

        for (TaskType t : ctx.planTasks.getTasks().getTask()) {
            int id = t.getId().intValue();
            Event commitEvent = ledger.findLastEvent(String.valueOf(id), EventType.COMMIT_RECORDED);
            if (commitEvent == null) continue;

            String recordedBranch = commitEvent.metadata().get("branch");
            String integrationMode = commitEvent.metadata().get("integration_mode");
            if (recordedBranch != null && recordedBranch.startsWith("agent-work/")) {
                taskAgentBranch.put(id, recordedBranch);
            } else if (integrationMode == null && recordedBranch == null) {
                String fallbackBranch = "agent-work/" + id;
                if (git.branchExists(fallbackBranch)) {
                    taskAgentBranch.put(id, fallbackBranch);
                }
            }

            List<Integer> dependsOn = xmlService.parseDependsOn(xmlService.getDependsOn(ctx.planTasks, id));
            String branch = taskAgentBranch.get(id);
            Set<String> files = branch != null ? getFilesTouched(repoRoot.toFile(), branch) : Set.of();
            orderInputs.add(new TaskOrderInput(id, dependsOn, files));
        }

        return new TaskMerges(taskAgentBranch, orderInputs);
    }

    private List<Integer> computeOrder(List<TaskOrderInput> orderInputs) throws WorkflowException {
        try {
            return IntegrationOrder.compute(orderInputs);
        } catch (IllegalArgumentException e) {
            throw new WorkflowException(WorkflowErrorCode.INTEGRATION_ORDER_FAILED,
                    "integrate: ordering failed: " + e.getMessage(), e);
        }
    }

    private WorktreePrep prepareIntegrationWorktree(IntegrationContext ctx, List<Integer> order, boolean force)
            throws Exception {
        Set<Integer> alreadyIntegrated = resumeAlreadyIntegrated(ctx, order);

        boolean worktreePresent = git.worktreeExists(ctx.integrationRel);
        boolean branchPresent = git.branchExists(ctx.integrationBranch);

        if (force && (worktreePresent || branchPresent)) {
            clearExistingIntegration(ctx, worktreePresent, branchPresent);
            alreadyIntegrated.clear();
            worktreePresent = false;
        } else if (worktreePresent || branchPresent) {
            if (alreadyIntegrated.isEmpty()) {
                throw new WorkflowException(WorkflowErrorCode.INTEGRATION_WORKTREE_PRESENT,
                        "integrate: integration worktree already exists at " + ctx.integrationRel
                                + " with no recorded PATCH_INTEGRATED events."
                                + " Use --force to start fresh or manually remove " + ctx.integrationBranch + ".");
            }
            reporter.info("integrate: resuming — tasks already integrated: " + alreadyIntegrated);
            reporter.info("integrate: merge order: " + order);
        }

        if (!worktreePresent) {
            git.addWorktreeAt(ctx.integrationRel, ctx.integrationBranch, ctx.taskBranch);
            ctx.iLedger.recordIntegrationPlan(order, ctx.integrationBranch);
            reporter.info("integrate: created " + ctx.integrationBranch + " from " + ctx.taskBranch);
            reporter.info("integrate: merge order: " + order);
        } else if (!ctx.integrationDir.isDirectory()) {
            git.attachWorktree(ctx.integrationRel, ctx.integrationBranch);
        }

        return new WorktreePrep(alreadyIntegrated);
    }

    private Set<Integer> resumeAlreadyIntegrated(IntegrationContext ctx, List<Integer> order) throws IOException {
        int lastPlanEventIndex = ledger.findLastEventIndex(
                EventType.INTEGRATION_PLAN, Map.of("plan_id", String.valueOf(ctx.plan)));
        if (lastPlanEventIndex < 0) return new HashSet<>();

        Set<Integer> alreadyIntegrated = new HashSet<>();
        for (int id : order) {
            if (ledger.findLastEventAfter(String.valueOf(id), EventType.PATCH_INTEGRATED,
                    lastPlanEventIndex) != null) {
                alreadyIntegrated.add(id);
            }
        }
        return alreadyIntegrated;
    }

    private void clearExistingIntegration(IntegrationContext ctx, boolean worktreePresent, boolean branchPresent)
            throws IOException, InterruptedException {
        reporter.info("integrate: --force: removing existing integration worktree/branch.");
        if (worktreePresent) {
            git.removeWorktreeKeepBranch(ctx.integrationRel);
        }
        if (branchPresent) {
            git.deleteBranch(ctx.integrationBranch);
        }
    }

    /** Returns {@code true} if the loop completed without exceeding the failure budget. */
    private boolean mergeTaskLoop(IntegrationContext ctx, List<Integer> order,
            Map<Integer, String> taskAgentBranch, WorktreePrep prep) throws Exception {
        int totalFailures = 0;

        for (int taskId : order) {
            String agentBranch = taskAgentBranch.get(taskId);
            if (agentBranch == null) {
                reporter.info("integrate: task " + taskId + " was done directly on task branch — skipping.");
                continue;
            }
            if (prep.alreadyIntegrated.contains(taskId)) {
                reporter.info("integrate: task " + taskId + " already integrated — skipping.");
                continue;
            }

            boolean integrated = mergeOneTask(ctx, taskId, agentBranch);

            if (!integrated) {
                totalFailures++;
                if (totalFailures >= ctx.maxTotalFailures) {
                    reporter.warn("integrate: reached max total failures (" + ctx.maxTotalFailures
                            + "). Integration branch left at " + ctx.integrationBranch + " for inspection.");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean mergeOneTask(IntegrationContext ctx, int taskId, String agentBranch) throws Exception {
        Resolver resolver = resolverFor(ctx, taskId);

        String agentWorkSha = git.branchSha(agentBranch);
        String preMergeSha = git.headSha(ctx.integrationDir);

        reporter.info("integrate: merging task " + taskId + " (" + agentBranch + ")...");
        MergeResult mergeResult = git.mergeSquash(ctx.integrationDir, agentBranch);

        String taskName = xmlService.getTaskName(ctx.planTasks, taskId);
        String commitMsg = "task(" + taskId + "): " + taskName;

        if (mergeResult.clean()) {
            // merge --squash already staged changes; do not git add -A here.
            processes.run(ctx.integrationDir, "git", "commit", "-m", commitMsg);
            String integrationCommitSha = git.headSha(ctx.integrationDir);
            String verifyError = processes.runVerify(ctx.integrationDir, ctx.verifyCmd);
            if (verifyError == null) {
                ctx.iLedger.recordPatchIntegrated(taskId, integrationCommitSha, agentWorkSha);
                reporter.info("integrate: task " + taskId + " integrated at " + integrationCommitSha);
                return true;
            }
            reporter.warn("integrate: task " + taskId + " verify failed (clean merge). Invoking resolver.");
            return invokeResolver(ctx, resolver, new MergeAttempt(
                    taskId, taskName, agentBranch, agentWorkSha, preMergeSha, commitMsg,
                    null, verifyError));
        }

        reporter.warn("integrate: task " + taskId + " has conflicts: " + mergeResult.conflictedFiles());
        return invokeResolver(ctx, resolver, new MergeAttempt(
                taskId, taskName, agentBranch, agentWorkSha, preMergeSha, commitMsg,
                mergeResult.conflictedFiles(), null));
    }

    private Resolver resolverFor(IntegrationContext ctx, int taskId) {
        IntegrationOptions.ResolverFactory factory = ctx.resolverFactory != null ? ctx.resolverFactory
                : (tid, absPath) -> new SubagentResolver(
                        new LedgerSubagentRunner(ledger, absPath, tid), absPath);
        return factory.create(taskId, ctx.integrationAbs);
    }

    private IntegrationResult finalizeIntegration(IntegrationContext ctx, List<Integer> order,
            Map<Integer, String> taskAgentBranch) throws Exception {
        backfillTaskCommitShas(ctx);

        try {
            git.commitFile(repoRoot.toFile(), ctx.xmlFile.getPath(),
                    "chore(plan-" + ctx.plan + "): update task commit SHAs after integration");
            reporter.info("integrate: committed updated SHAs to " + ctx.xmlFile.getPath());
        } catch (IOException e) {
            reporter.warn("Warning: could not commit " + ctx.xmlFile.getPath() + ": " + e.getMessage());
        }

        for (Map.Entry<Integer, String> entry : taskAgentBranch.entrySet()) {
            try {
                git.deleteBranch(entry.getValue());
            } catch (IOException e) {
                reporter.warn("Warning: could not delete " + entry.getValue() + ": " + e.getMessage());
            }
        }

        git.removeWorktreeKeepBranch(ctx.integrationRel);

        String tipSha = git.branchSha(ctx.integrationBranch);
        ctx.iLedger.recordIntegrationComplete(tipSha, order);
        reporter.info("integrate: complete. Integration tip: " + tipSha);
        String ffCmd = "git merge --ff-only " + ctx.integrationBranch;
        reporter.info("integrate: fast-forward task branch with: " + ffCmd);

        return IntegrationResult.ok(tipSha, ffCmd);
    }

    private void backfillTaskCommitShas(IntegrationContext ctx) throws Exception {
        List<String> logLines = Arrays.asList(
                git.logOneline(ctx.integrationDir, ctx.taskBranch + ".." + ctx.integrationBranch)
                        .trim().split("\n"));
        Collections.reverse(logLines);
        for (String line : logLines) {
            if (line.isBlank()) continue;
            String[] parts = line.split(" ", 2);
            if (parts.length < 2) continue;
            String sha = parts[0];
            String msg = parts[1];
            if (msg.startsWith("task(")) {
                int closeParen = msg.indexOf(')');
                if (closeParen > 5) {
                    try {
                        int tid = Integer.parseInt(msg.substring(5, closeParen));
                        xmlService.setCommit(ctx.planTasks, tid, sha);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        xmlService.writePlanTasks(ctx.planTasks, ctx.xmlFile);
    }

    private boolean invokeResolver(IntegrationContext ctx, Resolver resolver, MergeAttempt attempt) throws Exception {
        int taskId = attempt.taskId;

        String diffText = "";
        String patchBlobSha = "";
        Event patchEvent = ledger.findLastEvent(String.valueOf(taskId), EventType.PATCH_EMITTED);
        if (patchEvent != null) {
            diffText = patchEvent.payload() != null ? patchEvent.payload() : "";
            patchBlobSha = patchEvent.metadata().getOrDefault("patch_blob_sha1", "");
        }

        String taskMarkdown = xmlService.sliceTaskMarkdown(ctx.plan, taskId);
        String verifyError = attempt.initialVerifyError;

        for (int i = 1; i <= ctx.maxLlmIterations; i++) {
            reporter.warn("integrate: resolver attempt " + i + "/" + ctx.maxLlmIterations + " for task " + taskId);

            ResolverContext rctx = new ResolverContext(
                    taskId, attempt.taskName, taskMarkdown, patchBlobSha,
                    diffText, attempt.conflictedFiles, verifyError);

            OneShotResult result = tryResolverOnce(ctx, resolver, attempt, rctx, verifyError);
            if (result.outcome() == OneShotResult.Outcome.INTEGRATED) {
                ctx.iLedger.recordPatchIntegrated(taskId, result.integrationCommitSha(), attempt.agentWorkSha);
                reporter.info("integrate: task " + taskId + " resolved and integrated at " + result.integrationCommitSha());
                return true;
            }
            reporter.warn("integrate: " + result.retryReason() + " on attempt " + i);
            verifyError = result.verifyError();
        }

        git.resetHard(ctx.integrationDir, attempt.preMergeSha);
        ctx.iLedger.recordIntegrationFailure(taskId, ctx.maxLlmIterations,
                verifyError != null ? verifyError : "no changes");
        reporter.warn("integrate: task " + taskId + " failed after " + ctx.maxLlmIterations + " resolver attempts.");
        return false;
    }

    private OneShotResult tryResolverOnce(IntegrationContext ctx, Resolver resolver,
            MergeAttempt attempt, ResolverContext rctx, String prevVerifyError) throws Exception {
        resolver.resolve(ctx.integrationDir, rctx);

        if (conflictMarkersRemain(ctx.integrationDir, attempt.conflictedFiles, 0)) {
            return OneShotResult.retry("resolver did not remove conflict markers", prevVerifyError);
        }

        String preResolverSha = git.headSha(ctx.integrationDir);
        String commitSha = git.commitAll(ctx.integrationDir, attempt.commitMsg + " [resolved]");
        if (commitSha.equals(preResolverSha)) {
            return OneShotResult.retry("resolver made no changes", prevVerifyError);
        }

        String verifyError = processes.runVerify(ctx.integrationDir, ctx.verifyCmd);
        if (verifyError == null) {
            return OneShotResult.integrated(commitSha);
        }
        reporter.warn("integrate: verify still failing after resolver attempt");
        return OneShotResult.retry("verify still failing", verifyError);
    }

    private record OneShotResult(Outcome outcome, String integrationCommitSha, String retryReason, String verifyError) {
        enum Outcome { INTEGRATED, RETRY }

        static OneShotResult integrated(String sha) { return new OneShotResult(Outcome.INTEGRATED, sha, null, null); }
        static OneShotResult retry(String reason, String verifyError) { return new OneShotResult(Outcome.RETRY, null, reason, verifyError); }
    }

    /** Returns true if the resolver did not clear the conflict markers in any of the conflicted files. */
    private boolean conflictMarkersRemain(File integrationDir, List<String> conflictedFiles, int attemptNumber)
            throws IOException {
        if (conflictedFiles == null || conflictedFiles.isEmpty()) return false;
        boolean markersRemain = false;
        for (String relPath : conflictedFiles) {
            File f = new File(integrationDir, relPath);
            if (!f.exists()) continue;
            String content = Files.readString(f.toPath());
            if (content.contains("<<<<<<<")) {
                reporter.warn("integrate: resolver did not remove conflict markers in "
                        + relPath + " (attempt " + attemptNumber + ")");
                markersRemain = true;
            }
        }
        return markersRemain;
    }

    private Set<String> getFilesTouched(File cwd, String branch) throws IOException, InterruptedException {
        String baseRef;
        try {
            baseRef = processes.capture(cwd, "git", "merge-base", "HEAD", branch).trim();
        } catch (IOException e) {
            return Set.of();
        }
        String out = processes.capture(cwd, "git", "diff", "--name-only", baseRef + ".." + branch);
        Set<String> files = new HashSet<>();
        for (String line : out.split("\n")) {
            String f = line.trim();
            if (!f.isEmpty()) files.add(f);
        }
        return files;
    }

    // ── inner records ──────────────────────────────────────────────────────────

    /** Snapshot of resolved configuration and per-call collaborators for one runIntegration call. */
    private record IntegrationContext(
            int plan,
            String taskBranch,
            String verifyCmd,
            int maxLlmIterations,
            int maxTotalFailures,
            IntegrationOptions.ResolverFactory resolverFactory,
            IntegrationLedger iLedger,
            File xmlFile,
            PlanTasks planTasks,
            String integrationBranch,
            String integrationRel,
            String integrationAbs,
            File integrationDir
    ) {}

    /** Output of resolveTaskMerges: which agent-work branches exist and the ordering inputs. */
    private record TaskMerges(Map<Integer, String> taskAgentBranch, List<TaskOrderInput> orderInputs) {}

    /** State the merge loop needs from the worktree-preparation phase. */
    private record WorktreePrep(Set<Integer> alreadyIntegrated) {}

    /** Parameter object for invokeResolver — one attempt to merge a single task. */
    private record MergeAttempt(
            int taskId,
            String taskName,
            String agentBranch,
            String agentWorkSha,
            String preMergeSha,
            String commitMsg,
            List<String> conflictedFiles,
            String initialVerifyError
    ) {}
}
