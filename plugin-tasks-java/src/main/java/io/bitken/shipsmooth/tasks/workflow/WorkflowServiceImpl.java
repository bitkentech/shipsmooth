package io.bitken.shipsmooth.tasks.workflow;

import io.bitken.shipsmooth.tasks.git.MergeResult;
import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.integration.IntegrationDefaults;
import io.bitken.shipsmooth.tasks.integration.IntegrationLedger;
import io.bitken.shipsmooth.tasks.integration.IntegrationOrder;
import io.bitken.shipsmooth.tasks.integration.LedgerSubagentRunner;
import io.bitken.shipsmooth.tasks.integration.Resolver;
import io.bitken.shipsmooth.tasks.integration.ResolverContext;
import io.bitken.shipsmooth.tasks.integration.SubagentResolver;
import io.bitken.shipsmooth.tasks.integration.TaskOrderInput;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.jaxb.TaskType;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * <p>Wires the domain services (WorktreeService, LedgerService, XmlService)
 * into one orchestration brain. Constructed with a no-arg default that points
 * at the current working directory; tests construct it the same way.
 */
public class WorkflowServiceImpl implements WorkflowService {

    private final Path repoRoot;
    private final ProcessRunner processes;

    public WorkflowServiceImpl() {
        this(Paths.get("."), new DefaultProcessRunner());
    }

    public WorkflowServiceImpl(Path repoRoot) {
        this(repoRoot, new DefaultProcessRunner());
    }

    public WorkflowServiceImpl(Path repoRoot, ProcessRunner processes) {
        this.repoRoot = repoRoot;
        this.processes = processes;
    }

    @Override
    public Path initializeWorker(int planId, String taskId, String baseSha) throws WorkflowException {
        try {
            WorktreeService git = new WorktreeService(repoRoot);

            String worktreeRel = ".agents/tasks/" + taskId;
            String branch = "agent-work/" + taskId;

            if (git.worktreeExists(worktreeRel)) {
                throw new WorkflowException(WorkflowErrorCode.WORKTREE_ALREADY_EXISTS,
                        "Error: worktree already exists at " + worktreeRel);
            }

            git.addWorktree(worktreeRel, branch, baseSha);

            String resolvedBase = (baseSha != null && !baseSha.isBlank()) ? baseSha : git.headSha();

            LedgerService ledger = new LedgerService(repoRoot);
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
            WorktreeService git = new WorktreeService(repoRoot);
            LedgerService ledger = new LedgerService(repoRoot);
            ledger.ensureLedgerFile();

            String worktreeRel = ".agents/tasks/" + taskId;
            String branch = "agent-work/" + taskId;
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
                        String logOutput = processes.capture(worktreeDir, "git", "log", "--oneline",
                                baseSha + ".." + currentBranchSha);
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

            XmlService xmlService = new XmlService();
            File xmlFile = new File(".agents/plans/plan-" + planId + "-tasks.xml");
            PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
            final String resolvedPreCommitBranchSha = preCommitBranchSha;
            String taskName = getTaskName(planTasks, Integer.parseInt(taskId));

            String headSha = git.headSha();

            // resetHard inverse only fires if a ledger or XML write fails after commitAll
            // succeeds — keeping the branch tip and the ledger consistent.
            Transaction tx = new Transaction();
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

            System.out.println("worker-finish: task " + taskId + " committed on " + branch + " at " + commitSha);
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
            System.out.println("integrate: all tasks were done directly on task branch — nothing to merge.");
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

    /** Snapshot of resolved configuration and per-call collaborators for one runIntegration call. */
    private record IntegrationContext(
            int plan,
            String taskBranch,
            String verifyCmd,
            int maxLlmIterations,
            int maxTotalFailures,
            IntegrationOptions.ResolverFactory resolverFactory,
            WorktreeService git,
            LedgerService ledger,
            IntegrationLedger iLedger,
            XmlService xmlService,
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

    private IntegrationContext buildContext(int plan, IntegrationOptions options) throws Exception {
        WorktreeService git = new WorktreeService(repoRoot);
        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();
        IntegrationLedger iLedger = new IntegrationLedger(ledger, plan);

        String taskBranch = options.taskBranch();
        if (taskBranch == null || taskBranch.isBlank()) {
            taskBranch = processes.capture(repoRoot.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        }

        String verifyCmd = (options.verifyCmd() != null && !options.verifyCmd().isBlank())
                ? options.verifyCmd() : IntegrationDefaults.VERIFY_CMD;

        XmlService xmlService = new XmlService();
        File xmlFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);

        String integrationBranch = "integration/plan-" + plan;
        String integrationRel = ".agents/integration/plan-" + plan;
        String integrationAbs = repoRoot.resolve(integrationRel).toAbsolutePath().toString();
        File integrationDir = repoRoot.resolve(integrationRel).toFile();

        return new IntegrationContext(
                plan, taskBranch, verifyCmd,
                options.maxLlmIterations(), options.maxTotalFailures(), options.resolverFactory(),
                git, ledger, iLedger, xmlService, xmlFile, planTasks,
                integrationBranch, integrationRel, integrationAbs, integrationDir);
    }

    private TaskMerges resolveTaskMerges(IntegrationContext ctx) throws IOException, InterruptedException {
        Map<Integer, String> taskAgentBranch = new HashMap<>();
        List<TaskOrderInput> orderInputs = new ArrayList<>();

        for (TaskType t : ctx.planTasks.getTasks().getTask()) {
            int id = t.getId().intValue();
            Event commitEvent = ctx.ledger.findLastEvent(String.valueOf(id), EventType.COMMIT_RECORDED);
            if (commitEvent == null) continue;

            String recordedBranch = commitEvent.metadata().get("branch");
            String integrationMode = commitEvent.metadata().get("integration_mode");
            if (recordedBranch != null && recordedBranch.startsWith("agent-work/")) {
                taskAgentBranch.put(id, recordedBranch);
            } else if (integrationMode == null && recordedBranch == null) {
                String fallbackBranch = "agent-work/" + id;
                if (ctx.git.branchExists(fallbackBranch)) {
                    taskAgentBranch.put(id, fallbackBranch);
                }
            }

            List<Integer> dependsOn = parseDependsOn(ctx.xmlService.getDependsOn(ctx.planTasks, id));
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

        boolean worktreePresent = ctx.git.worktreeExists(ctx.integrationRel);
        boolean branchPresent = ctx.git.branchExists(ctx.integrationBranch);

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
            System.out.println("integrate: resuming — tasks already integrated: " + alreadyIntegrated);
            System.out.println("integrate: merge order: " + order);
        }

        if (!worktreePresent) {
            ctx.git.addWorktreeAt(ctx.integrationRel, ctx.integrationBranch, ctx.taskBranch);
            ctx.iLedger.recordIntegrationPlan(order, ctx.integrationBranch);
            System.out.println("integrate: created " + ctx.integrationBranch + " from " + ctx.taskBranch);
            System.out.println("integrate: merge order: " + order);
        } else if (!ctx.integrationDir.isDirectory()) {
            processes.run(repoRoot.toFile(), "git", "worktree", "add", ctx.integrationRel, ctx.integrationBranch);
        }

        return new WorktreePrep(alreadyIntegrated);
    }

    private Set<Integer> resumeAlreadyIntegrated(IntegrationContext ctx, List<Integer> order) throws IOException {
        int lastPlanEventIndex = ctx.ledger.findLastEventIndex(
                EventType.INTEGRATION_PLAN, Map.of("plan_id", String.valueOf(ctx.plan)));
        if (lastPlanEventIndex < 0) return new HashSet<>();

        Set<Integer> alreadyIntegrated = new HashSet<>();
        for (int id : order) {
            if (ctx.ledger.findLastEventAfter(String.valueOf(id), EventType.PATCH_INTEGRATED,
                    lastPlanEventIndex) != null) {
                alreadyIntegrated.add(id);
            }
        }
        return alreadyIntegrated;
    }

    private void clearExistingIntegration(IntegrationContext ctx, boolean worktreePresent, boolean branchPresent)
            throws IOException, InterruptedException {
        System.out.println("integrate: --force: removing existing integration worktree/branch.");
        if (worktreePresent) {
            ctx.git.removeWorktreeKeepBranch(ctx.integrationRel);
        }
        if (branchPresent) {
            ctx.git.deleteBranch(ctx.integrationBranch);
        }
    }

    /** Returns {@code true} if the loop completed without exceeding the failure budget. */
    private boolean mergeTaskLoop(IntegrationContext ctx, List<Integer> order,
            Map<Integer, String> taskAgentBranch, WorktreePrep prep) throws Exception {
        int totalFailures = 0;

        for (int taskId : order) {
            String agentBranch = taskAgentBranch.get(taskId);
            if (agentBranch == null) {
                System.out.println("integrate: task " + taskId + " was done directly on task branch — skipping.");
                continue;
            }
            if (prep.alreadyIntegrated.contains(taskId)) {
                System.out.println("integrate: task " + taskId + " already integrated — skipping.");
                continue;
            }

            boolean integrated = mergeOneTask(ctx, taskId, agentBranch);

            if (!integrated) {
                totalFailures++;
                if (totalFailures >= ctx.maxTotalFailures) {
                    System.err.println("integrate: reached max total failures (" + ctx.maxTotalFailures
                            + "). Integration branch left at " + ctx.integrationBranch + " for inspection.");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean mergeOneTask(IntegrationContext ctx, int taskId, String agentBranch) throws Exception {
        Resolver resolver = resolverFor(ctx, taskId);

        String agentWorkSha = ctx.git.branchSha(agentBranch);
        String preMergeSha = ctx.git.headSha(ctx.integrationDir);

        System.out.println("integrate: merging task " + taskId + " (" + agentBranch + ")...");
        MergeResult mergeResult = ctx.git.mergeSquash(ctx.integrationDir, agentBranch);

        String taskName = getTaskName(ctx.planTasks, taskId);
        String commitMsg = "task(" + taskId + "): " + taskName;

        if (mergeResult.clean()) {
            // merge --squash already staged changes; do not git add -A here.
            processes.run(ctx.integrationDir, "git", "commit", "-m", commitMsg);
            String integrationCommitSha = ctx.git.headSha(ctx.integrationDir);
            String verifyError = processes.runVerify(ctx.integrationDir, ctx.verifyCmd);
            if (verifyError == null) {
                ctx.iLedger.recordPatchIntegrated(taskId, integrationCommitSha, agentWorkSha);
                System.out.println("integrate: task " + taskId + " integrated at " + integrationCommitSha);
                return true;
            }
            System.err.println("integrate: task " + taskId + " verify failed (clean merge). Invoking resolver.");
            return invokeResolver(ctx, resolver, new MergeAttempt(
                    taskId, taskName, agentBranch, agentWorkSha, preMergeSha, commitMsg,
                    null, verifyError));
        }

        System.err.println("integrate: task " + taskId + " has conflicts: " + mergeResult.conflictedFiles());
        return invokeResolver(ctx, resolver, new MergeAttempt(
                taskId, taskName, agentBranch, agentWorkSha, preMergeSha, commitMsg,
                mergeResult.conflictedFiles(), null));
    }

    private Resolver resolverFor(IntegrationContext ctx, int taskId) {
        IntegrationOptions.ResolverFactory factory = ctx.resolverFactory != null ? ctx.resolverFactory
                : (tid, absPath) -> new SubagentResolver(
                        new LedgerSubagentRunner(ctx.ledger, absPath, tid), absPath);
        return factory.create(taskId, ctx.integrationAbs);
    }

    private IntegrationResult finalizeIntegration(IntegrationContext ctx, List<Integer> order,
            Map<Integer, String> taskAgentBranch) throws Exception {
        backfillTaskCommitShas(ctx);

        try {
            processes.run(repoRoot.toFile(), "git", "add", ctx.xmlFile.getPath());
            String status = processes.capture(repoRoot.toFile(), "git", "status", "--porcelain",
                    ctx.xmlFile.getPath()).trim();
            if (!status.isEmpty()) {
                processes.run(repoRoot.toFile(), "git", "commit", "-m",
                        "chore(plan-" + ctx.plan + "): update task commit SHAs after integration");
                System.out.println("integrate: committed updated SHAs to " + ctx.xmlFile.getPath());
            }
        } catch (IOException e) {
            System.err.println("Warning: could not commit " + ctx.xmlFile.getPath() + ": " + e.getMessage());
        }

        for (Map.Entry<Integer, String> entry : taskAgentBranch.entrySet()) {
            try {
                ctx.git.deleteBranch(entry.getValue());
            } catch (IOException e) {
                System.err.println("Warning: could not delete " + entry.getValue() + ": " + e.getMessage());
            }
        }

        ctx.git.removeWorktreeKeepBranch(ctx.integrationRel);

        String tipSha = ctx.git.branchSha(ctx.integrationBranch);
        ctx.iLedger.recordIntegrationComplete(tipSha, order);
        System.out.println("integrate: complete. Integration tip: " + tipSha);
        String ffCmd = "git merge --ff-only " + ctx.integrationBranch;
        System.out.println("integrate: fast-forward task branch with: " + ffCmd);

        return IntegrationResult.ok(tipSha, ffCmd);
    }

    private void backfillTaskCommitShas(IntegrationContext ctx) throws Exception {
        List<String> logLines = Arrays.asList(
                processes.capture(ctx.integrationDir, "git", "log", "--oneline",
                        ctx.taskBranch + ".." + ctx.integrationBranch).trim().split("\n"));
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
                        ctx.xmlService.setCommit(ctx.planTasks, tid, sha);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        ctx.xmlService.writePlanTasks(ctx.planTasks, ctx.xmlFile);
    }

    private boolean invokeResolver(IntegrationContext ctx, Resolver resolver, MergeAttempt attempt) throws Exception {
        int taskId = attempt.taskId;
        File integrationDir = ctx.integrationDir;

        String diffText = "";
        String patchBlobSha = "";
        Event patchEvent = ctx.ledger.findLastEvent(String.valueOf(taskId), EventType.PATCH_EMITTED);
        if (patchEvent != null) {
            diffText = patchEvent.payload() != null ? patchEvent.payload() : "";
            patchBlobSha = patchEvent.metadata().getOrDefault("patch_blob_sha1", "");
        }

        String taskMarkdown = sliceTaskMarkdown(ctx.plan, taskId);
        String verifyError = attempt.initialVerifyError;

        for (int i = 1; i <= ctx.maxLlmIterations; i++) {
            System.err.println("integrate: resolver attempt " + i + "/" + ctx.maxLlmIterations + " for task " + taskId);

            ResolverContext rctx = new ResolverContext(
                    taskId, attempt.taskName, taskMarkdown, patchBlobSha,
                    diffText, attempt.conflictedFiles, verifyError);

            resolver.resolve(integrationDir, rctx);

            if (conflictMarkersRemain(integrationDir, attempt.conflictedFiles, i)) {
                if (i == ctx.maxLlmIterations) break;
                continue;
            }

            String preResolverCommitSha = ctx.git.headSha(integrationDir);
            String integrationCommitSha = ctx.git.commitAll(integrationDir, attempt.commitMsg + " [resolved]");
            if (integrationCommitSha.equals(preResolverCommitSha)) {
                System.err.println("integrate: resolver made no changes on attempt " + i);
                if (i == ctx.maxLlmIterations) break;
                continue;
            }

            verifyError = processes.runVerify(integrationDir, ctx.verifyCmd);
            if (verifyError == null) {
                ctx.iLedger.recordPatchIntegrated(taskId, integrationCommitSha, attempt.agentWorkSha);
                System.out.println("integrate: task " + taskId + " resolved and integrated at " + integrationCommitSha);
                return true;
            }
            System.err.println("integrate: verify still failing after resolver attempt " + i);
        }

        ctx.git.resetHard(integrationDir, attempt.preMergeSha);
        ctx.iLedger.recordIntegrationFailure(taskId, ctx.maxLlmIterations,
                verifyError != null ? verifyError : "no changes");
        System.err.println("integrate: task " + taskId + " failed after " + ctx.maxLlmIterations + " resolver attempts.");
        return false;
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
                System.err.println("integrate: resolver did not remove conflict markers in "
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

    private List<Integer> parseDependsOn(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String part : s.split(",")) {
            try { result.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private String getTaskName(PlanTasks planTasks, int taskId) {
        return planTasks.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == taskId)
                .map(t -> t.getName() != null ? t.getName() : String.valueOf(taskId))
                .findFirst().orElse(String.valueOf(taskId));
    }

    private String sliceTaskMarkdown(int planId, int taskId) {
        try {
            File planFile = new File(".agents/plans/plan-" + planId + ".md");
            if (!planFile.exists()) return "";
            String content = Files.readString(planFile.toPath());
            String marker = "### Task " + taskId + ":";
            int start = content.indexOf(marker);
            if (start < 0) return "";
            int next = content.indexOf("### Task ", start + marker.length());
            return next > 0 ? content.substring(start, next).trim() : content.substring(start).trim();
        } catch (IOException e) {
            return "";
        }
    }
}