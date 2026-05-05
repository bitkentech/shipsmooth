package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.jaxb.PlanTasks;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "worker-finish", description = "Capture subagent diff, commit on worktree branch, record ledger events.")
public class WorkerFinishCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private String task;

    @Override
    public Integer call() throws Exception {
        var repoRoot = Paths.get(".");
        WorktreeService git = new WorktreeService(repoRoot);
        LedgerService ledger = new LedgerService(repoRoot);
        ledger.ensureLedgerFile();

        String worktreeRel = ".agents/tasks/" + task;
        String branch = "agent-work/" + task;
        File worktreeDir = repoRoot.resolve(worktreeRel).toFile();

        if (!worktreeDir.isDirectory()) {
            System.err.println("Error: worktree directory not found: " + worktreeRel);
            return 1;
        }

        // --- Guard: ensure subagent made no commits ---
        Event wtEvent = ledger.findLastEvent(task, EventType.WORKTREE_CREATED);
        if (wtEvent != null) {
            String baseSha = wtEvent.baseCommitSha();
            if (baseSha != null && !baseSha.isBlank()) {
                // branchSha resolves the branch tip in the *repo*, not the worktree
                String currentBranchSha = git.branchSha(branch);
                if (!baseSha.equals(currentBranchSha)) {
                    // Subagent committed — find the rogue commits
                    String logOutput = captureGit(worktreeDir, "git", "log", "--oneline",
                            baseSha + ".." + currentBranchSha);
                    System.err.println("worker-finish: subagent for task " + task
                            + " created commits in the worktree.");
                    System.err.println("This violates the contract: subagents must not run git.");
                    System.err.println("Recorded commits:\n" + logOutput.trim());
                    System.err.println("Aborting; no PATCH_EMITTED or COMMIT_RECORDED event written.");
                    return 1;
                }
            }
        }

        // --- Happy path ---
        String diff = git.diff(worktreeDir);
        if (diff.isBlank()) {
            System.err.println("worker-finish: subagent produced no changes (empty diff). Aborting.");
            return 1;
        }

        // Derive task name from XML for the commit message
        XmlService xmlService = new XmlService();
        File xmlFile = new File(".agents/plans/plan-" + plan + "-tasks.xml");
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        String taskName = planTasks.getTasks().getTask().stream()
                .filter(t -> String.valueOf(t.getId()).equals(task))
                .map(t -> t.getName() != null ? t.getName() : task)
                .findFirst().orElse(task);

        // Commit on the worktree branch
        String headSha = git.headSha();
        String commitSha = git.commitAll(worktreeDir, "agent: task " + task + " - " + taskName);

        // Record ledger events
        ledger.record(Event.forTask(EventType.PATCH_EMITTED, task, headSha, diff, Map.of()));
        ledger.record(Event.forTask(EventType.COMMIT_RECORDED, task, headSha, commitSha,
                Map.of("branch", branch, "commit_sha", commitSha)));

        // Update XML <commit> field
        xmlService.setCommit(planTasks, Integer.parseInt(task), commitSha);
        xmlService.writePlanTasks(planTasks, xmlFile);

        System.out.println("worker-finish: task " + task + " committed on " + branch + " at " + commitSha);
        return 0;
    }

    private String captureGit(File cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        return out;
    }
}