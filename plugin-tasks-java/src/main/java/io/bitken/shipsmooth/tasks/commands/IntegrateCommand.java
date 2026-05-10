package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.MergeResult;
import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.integration.*;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.jaxb.TaskType;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Command(name = "integrate", description = "Integrate parallel agent-work/* branches into the task branch.")
public class IntegrateCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task-branch")
    private String taskBranch;

    @Option(names = "--verify-cmd")
    private String verifyCmd;

    @Option(names = "--max-llm-iterations")
    private int maxLlmIterations = IntegrationDefaults.MAX_LLM_ITERATIONS;

    @Option(names = "--max-total-failures")
    private int maxTotalFailures = IntegrationDefaults.MAX_TOTAL_FAILURES;

    @Override
    public Integer call() throws Exception {
        Path repoRoot = Paths.get(".");
        WorktreeService git = new WorktreeService(repoRoot);
        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();
        IntegrationLedger iLedger = new IntegrationLedger(ledger, plan);

        // Resolve task branch
        if (taskBranch == null || taskBranch.isBlank()) {
            taskBranch = captureGit(repoRoot.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        }

        // Resolve verify command
        String effectiveVerifyCmd = (verifyCmd != null && !verifyCmd.isBlank())
                ? verifyCmd : IntegrationDefaults.VERIFY_CMD;

        // Load XML
        XmlService xmlService = new XmlService();
        File xmlFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);

        // Read COMMIT_RECORDED events from ledger to determine which tasks have agent-work branches.
        // Tasks recorded with integration_mode=worktree have an agent-work/{id} branch to merge.
        // Tasks recorded with integration_mode=direct (or no metadata) were done on the task branch
        // and need no merge.
        Map<Integer, String> taskAgentBranch = new HashMap<>();
        for (TaskType t : planTasks.getTasks().getTask()) {
            int id = t.getId().intValue();
            Event commitEvent = ledger.findLastEvent(String.valueOf(id), EventType.COMMIT_RECORDED);
            if (commitEvent == null) continue;
            String recordedBranch = commitEvent.metadata().get("branch");
            String integrationMode = commitEvent.metadata().get("integration_mode");
            // worktree mode: branch recorded explicitly as agent-work/{id}
            if (recordedBranch != null && recordedBranch.startsWith("agent-work/")) {
                taskAgentBranch.put(id, recordedBranch);
            } else if (integrationMode == null && recordedBranch == null) {
                // Legacy: no metadata — fall back to checking if agent-work/{id} exists
                String fallbackBranch = "agent-work/" + id;
                if (branchExists(repoRoot.toFile(), fallbackBranch)) {
                    taskAgentBranch.put(id, fallbackBranch);
                }
            }
            // integration_mode=direct or branch not agent-work/* → skip (already on task branch)
        }

        // Build TaskOrderInput list for dependency resolution.
        // All tasks with a COMMIT_RECORDED event are included so the dependency validator
        // accepts them as known. Tasks without an agent-work branch get empty filesTouched
        // and will be skipped during the merge loop.
        List<TaskOrderInput> orderInputs = new ArrayList<>();
        for (TaskType t : planTasks.getTasks().getTask()) {
            int id = t.getId().intValue();
            Event commitEvent = ledger.findLastEvent(String.valueOf(id), EventType.COMMIT_RECORDED);
            if (commitEvent == null) continue;
            String dependsOnStr = xmlService.getDependsOn(planTasks, id);
            List<Integer> dependsOn = parseDependsOn(dependsOnStr);
            String branch = taskAgentBranch.get(id);
            Set<String> files = branch != null ? getFilesTouched(repoRoot.toFile(), branch) : Set.of();
            orderInputs.add(new TaskOrderInput(id, dependsOn, files));
        }

        if (orderInputs.isEmpty()) {
            System.err.println("integrate: no COMMIT_RECORDED events found for plan " + plan + ". Nothing to integrate.");
            return 1;
        }
        if (taskAgentBranch.isEmpty()) {
            System.out.println("integrate: all tasks were done directly on the task branch — nothing to merge.");
            return 0;
        }

        List<Integer> order;
        try {
            order = IntegrationOrder.compute(orderInputs);
        } catch (IllegalArgumentException e) {
            System.err.println("integrate: ordering failed: " + e.getMessage());
            return 1;
        }

        // Create integration worktree
        String integrationBranch = "integration/plan-" + plan;
        String integrationRel = ".agents/integration/plan-" + plan;
        String integrationAbs = repoRoot.resolve(integrationRel).toAbsolutePath().toString();

        if (git.worktreeExists(integrationRel)) {
            System.err.println("integrate: integration worktree already exists at " + integrationRel
                    + ". Remove it or delete branch " + integrationBranch + " before retrying.");
            return 1;
        }

        // Fork integration branch from current task branch tip
        git.addWorktreeAt(integrationRel, integrationBranch, taskBranch);
        File integrationDir = repoRoot.resolve(integrationRel).toFile();

        iLedger.recordIntegrationPlan(order, integrationBranch);
        System.out.println("integrate: created " + integrationBranch + " from " + taskBranch);
        System.out.println("integrate: merge order: " + order);

        int totalFailures = 0;

        for (int taskId : order) {
            String agentBranch = taskAgentBranch.get(taskId);
            if (agentBranch == null) {
                System.out.println("integrate: task " + taskId + " was done directly on task branch — skipping.");
                continue;
            }
            // Fresh runner per task so the ledger poll is keyed to the correct task id
            Resolver resolver = new SubagentResolver(
                    new LedgerSubagentRunner(ledger, integrationAbs, taskId), integrationAbs);

            String agentWorkSha = captureGit(repoRoot.toFile(), "git", "rev-parse", agentBranch).trim();
            String presMergeSha = captureGit(integrationDir, "git", "rev-parse", "HEAD").trim();

            System.out.println("integrate: merging task " + taskId + " (" + agentBranch + ")...");
            MergeResult mergeResult = git.mergeSquash(integrationDir, agentBranch);

            String taskName = getTaskName(planTasks, taskId);
            String commitMsg = "task(" + taskId + "): " + taskName;

            boolean integrated = false;

            if (mergeResult.clean()) {
                // Commit the squash
                runGit(integrationDir, "git", "commit", "-m", commitMsg);
                String integrationCommitSha = captureGit(integrationDir, "git", "rev-parse", "HEAD").trim();

                // Verify
                String verifyError = runVerify(integrationDir, effectiveVerifyCmd);
                if (verifyError == null) {
                    iLedger.recordPatchIntegrated(taskId, integrationCommitSha, agentWorkSha);
                    System.out.println("integrate: task " + taskId + " integrated at " + integrationCommitSha);
                    integrated = true;
                } else {
                    // Verify failed on clean merge — invoke resolver
                    System.err.println("integrate: task " + taskId + " verify failed (clean merge). Invoking resolver.");
                    integrated = invokeResolver(taskId, taskName, planTasks, xmlService, ledger,
                            agentBranch, agentWorkSha, presMergeSha, integrationDir, integrationAbs,
                            git, iLedger, resolver, effectiveVerifyCmd, null, verifyError, commitMsg);
                }
            } else {
                // Conflict
                System.err.println("integrate: task " + taskId + " has conflicts: " + mergeResult.conflictedFiles());
                integrated = invokeResolver(taskId, taskName, planTasks, xmlService, ledger,
                        agentBranch, agentWorkSha, presMergeSha, integrationDir, integrationAbs,
                        git, iLedger, resolver, effectiveVerifyCmd, mergeResult.conflictedFiles(), null, commitMsg);
            }

            if (!integrated) {
                totalFailures++;
                if (totalFailures >= maxTotalFailures) {
                    System.err.println("integrate: reached max total failures (" + maxTotalFailures
                            + "). Integration branch left at " + integrationBranch + " for inspection.");
                    return 1;
                }
            }
        }

        // All tasks integrated — update XML <commit> and clean up
        List<String> logLines = Arrays.asList(
                captureGit(integrationDir, "git", "log", "--oneline", taskBranch + ".." + integrationBranch)
                        .trim().split("\n"));
        Collections.reverse(logLines); // oldest first
        for (String line : logLines) {
            if (line.isBlank()) continue;
            // Format: "<sha> task(N): ..."
            String[] parts = line.split(" ", 2);
            if (parts.length < 2) continue;
            String sha = parts[0];
            String msg = parts[1];
            // Parse task id from "task(N): ..."
            if (msg.startsWith("task(")) {
                int closeParen = msg.indexOf(')');
                if (closeParen > 5) {
                    try {
                        int tid = Integer.parseInt(msg.substring(5, closeParen));
                        xmlService.setCommit(planTasks, tid, sha);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        xmlService.writePlanTasks(planTasks, xmlFile);

        // Stage and commit the XML change on the task branch
        try {
            runGit(repoRoot.toFile(), "git", "add", xmlFile.getPath());
            // Check if there are actually changes to commit (to avoid error)
            String status = captureGit(repoRoot.toFile(), "git", "status", "--porcelain", xmlFile.getPath()).trim();
            if (!status.isEmpty()) {
                runGit(repoRoot.toFile(), "git", "commit", "-m",
                        "chore(plan-" + plan + "): update task commit SHAs after integration");
                System.out.println("integrate: committed updated SHAs to " + xmlFile.getPath());
            }
        } catch (IOException e) {
            System.err.println("Warning: could not commit " + xmlFile.getPath() + ": " + e.getMessage());
        }

        // Delete agent-work/* branches (only those that were actually merged)
        for (Map.Entry<Integer, String> entry : taskAgentBranch.entrySet()) {
            try {
                runGit(repoRoot.toFile(), "git", "branch", "-D", entry.getValue());
            } catch (IOException e) {
                System.err.println("Warning: could not delete " + entry.getValue() + ": " + e.getMessage());
            }
        }

        // Remove integration worktree (keep branch for fast-forward)
        git.removeWorktreeKeepBranch(integrationRel);

        String tipSha = captureGit(repoRoot.toFile(), "git", "rev-parse", integrationBranch).trim();
        iLedger.recordIntegrationComplete(tipSha, order);
        System.out.println("integrate: complete. Integration tip: " + tipSha);
        System.out.println("integrate: fast-forward task branch with: git merge --ff-only " + integrationBranch);

        return 0;
    }

    private boolean invokeResolver(int taskId, String taskName, PlanTasks planTasks,
            XmlService xmlService, LedgerService ledger,
            String agentBranch, String agentWorkSha, String preMergeSha,
            File integrationDir, String integrationAbs,
            WorktreeService git, IntegrationLedger iLedger, Resolver resolver,
            String effectiveVerifyCmd, List<String> conflictedFiles, String initialVerifyError,
            String commitMsg) throws Exception {

        // Fetch the original diff from the PATCH_EMITTED ledger event
        String diffText = "";
        String patchBlobSha = "";
        Event patchEvent = ledger.findLastEvent(String.valueOf(taskId), EventType.PATCH_EMITTED);
        if (patchEvent != null) {
            diffText = patchEvent.payload() != null ? patchEvent.payload() : "";
            patchBlobSha = patchEvent.metadata().getOrDefault("patch_blob_sha1", "");
        }

        // Slice task markdown from plan file
        String taskMarkdown = sliceTaskMarkdown(taskId);

        String verifyError = initialVerifyError;

        for (int attempt = 1; attempt <= maxLlmIterations; attempt++) {
            System.err.println("integrate: resolver attempt " + attempt + "/" + maxLlmIterations + " for task " + taskId);

            ResolverContext ctx = new ResolverContext(
                    taskId, taskName, taskMarkdown, patchBlobSha,
                    diffText, conflictedFiles, verifyError);

            resolver.resolve(integrationDir, ctx);

            // Stage and commit resolver's changes
            runGit(integrationDir, "git", "add", "-A");
            String status = captureGit(integrationDir, "git", "status", "--porcelain").trim();
            if (status.isEmpty()) {
                System.err.println("integrate: resolver made no changes on attempt " + attempt);
                if (attempt == maxLlmIterations) break;
                continue;
            }
            runGit(integrationDir, "git", "commit", "-m", commitMsg + " [resolved]");
            String integrationCommitSha = captureGit(integrationDir, "git", "rev-parse", "HEAD").trim();

            verifyError = runVerify(integrationDir, effectiveVerifyCmd);
            if (verifyError == null) {
                iLedger.recordPatchIntegrated(taskId, integrationCommitSha, agentWorkSha);
                System.out.println("integrate: task " + taskId + " resolved and integrated at " + integrationCommitSha);
                return true;
            }
            System.err.println("integrate: verify still failing after resolver attempt " + attempt);
        }

        // Give up — roll back
        git.resetHard(integrationDir, preMergeSha);
        iLedger.recordIntegrationFailure(taskId, maxLlmIterations, verifyError != null ? verifyError : "no changes");
        System.err.println("integrate: task " + taskId + " failed after " + maxLlmIterations + " resolver attempts.");
        return false;
    }

    /** Returns null on success, or the tail of stderr/stdout on failure. */
    private String runVerify(File cwd, String verifyCmd) throws IOException, InterruptedException {
        // Delegate to the shell so quotes and globs in verifyCmd are handled correctly.
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = os.contains("win")
                ? new String[]{"cmd", "/c", verifyCmd}
                : new String[]{"sh", "-c", verifyCmd};
        Process p = new ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(5, TimeUnit.MINUTES);
        if (p.exitValue() == 0) return null;
        // Return last 50 lines
        String[] lines = output.split("\n");
        int start = Math.max(0, lines.length - 50);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    private Set<String> getFilesTouched(File cwd, String branch) throws IOException, InterruptedException {
        // Files changed between branch's fork point and branch tip
        String baseRef;
        try {
            baseRef = captureGit(cwd, "git", "merge-base", "HEAD", branch).trim();
        } catch (IOException e) {
            return Set.of();
        }
        String out = captureGit(cwd, "git", "diff", "--name-only", baseRef + ".." + branch);
        Set<String> files = new HashSet<>();
        for (String line : out.split("\n")) {
            String f = line.trim();
            if (!f.isEmpty()) files.add(f);
        }
        return files;
    }

    private boolean branchExists(File cwd, String branch) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "rev-parse", "--verify", branch)
                .directory(cwd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(10, TimeUnit.SECONDS);
        return p.exitValue() == 0;
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

    private String sliceTaskMarkdown(int taskId) {
        try {
            File planFile = new File(".agents/plans/plan-" + plan + ".md");
            if (!planFile.exists()) return "";
            String content = new String(java.nio.file.Files.readAllBytes(planFile.toPath()), StandardCharsets.UTF_8);
            // Find "### Task N:" and slice to next "### Task" or end of tasks section
            String marker = "### Task " + taskId + ":";
            int start = content.indexOf(marker);
            if (start < 0) return "";
            int next = content.indexOf("### Task ", start + marker.length());
            return next > 0 ? content.substring(start, next).trim() : content.substring(start).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void runGit(File cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        if (p.exitValue() != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private String captureGit(File cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        if (p.exitValue() != 0) {
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + err);
        }
        return out;
    }
}