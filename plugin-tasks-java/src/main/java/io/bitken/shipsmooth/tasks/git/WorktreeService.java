package io.bitken.shipsmooth.tasks.git;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Low-level git worktree operations.
 */
public class WorktreeService {

    private final Semaphore gitGate = new Semaphore(4);
    private final Path repoRoot;

    public WorktreeService(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public String headSha() throws IOException, InterruptedException {
        return capture(repoRoot.toFile(), "git", "rev-parse", "HEAD").trim();
    }

    /** Returns the HEAD SHA of the named branch, resolving it in the repo. */
    public String branchSha(String branch) throws IOException, InterruptedException {
        return capture(repoRoot.toFile(), "git", "rev-parse", branch).trim();
    }

    public void addWorktree(String relativePath, String branch) throws IOException, InterruptedException {
        addWorktree(relativePath, branch, null);
    }

    /** When baseSha is non-blank, the worktree starts at that commit instead of HEAD. */
    public void addWorktree(String relativePath, String branch, String baseSha) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            if (baseSha != null && !baseSha.isBlank()) {
                run(repoRoot.toFile(), "git", "worktree", "add", relativePath, "-b", branch, baseSha);
            } else {
                run(repoRoot.toFile(), "git", "worktree", "add", relativePath, "-b", branch);
            }
        } finally {
            gitGate.release();
        }
    }

    /** Remove worktree directory but keep the branch ref. */
    public void removeWorktreeKeepBranch(String relativePath) throws IOException, InterruptedException {
        gitGate.acquireUninterruptibly();
        try {
            try {
                run(repoRoot.toFile(), "git", "worktree", "remove", "--force", relativePath);
            } catch (IOException e) {
                System.err.println("Warning: worktree remove failed for " + relativePath + ": " + e.getMessage());
            }
        } finally {
            gitGate.release();
        }
    }

    /** Remove worktree directory AND delete the branch. */
    public void removeWorktree(String relativePath, String branch) {
        gitGate.acquireUninterruptibly();
        try {
            try {
                run(repoRoot.toFile(), "git", "worktree", "remove", "--force", relativePath);
            } catch (Exception e) {
                System.err.println("Warning: worktree remove failed for " + relativePath + ": " + e.getMessage());
            }
            try {
                run(repoRoot.toFile(), "git", "branch", "-D", branch);
            } catch (Exception e) {
                System.err.println("Warning: branch delete failed for " + branch + ": " + e.getMessage());
            }
        } finally {
            gitGate.release();
        }
    }

    /**
     * Stage all changes in worktreeDir, capture the diff, then commit.
     * Returns the new HEAD SHA. If nothing to commit, returns the existing HEAD SHA.
     */
    public String commitAll(File worktreeDir, String message) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            run(worktreeDir, "git", "add", "-A");
            String status = capture(worktreeDir, "git", "status", "--porcelain").trim();
            if (status.isEmpty()) {
                return capture(worktreeDir, "git", "rev-parse", "HEAD").trim();
            }
            run(worktreeDir, "git", "commit", "-q", "-m", message);
            return capture(worktreeDir, "git", "rev-parse", "HEAD").trim();
        } finally {
            gitGate.release();
        }
    }

    /**
     * Stage all changes in worktreeDir and return the unified diff.
     * Does NOT commit.
     */
    public String diff(File worktreeDir) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            run(worktreeDir, "git", "add", "-A");
            return capture(worktreeDir, "git", "diff", "--cached");
        } finally {
            gitGate.release();
        }
    }

    /** Creates a worktree from a named ref (branch name or tag), not necessarily HEAD. */
    public void addWorktreeAt(String relativePath, String branch, String baseRef)
            throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            run(repoRoot.toFile(), "git", "worktree", "add", relativePath, "-b", branch, baseRef);
        } finally {
            gitGate.release();
        }
    }

    /**
     * Attempts git merge --squash of the named branch into worktreeDir.
     * Does NOT commit — caller stages and commits after resolution.
     * Returns clean=true on success; clean=false with conflicted file list on conflict.
     */
    public MergeResult mergeSquash(File worktreeDir, String branch)
            throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            Process p = new ProcessBuilder("git", "merge", "--squash", branch)
                    .directory(worktreeDir)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes(); // drain
            p.waitFor(60, TimeUnit.SECONDS);
            if (p.exitValue() == 0) {
                return MergeResult.success();
            }
            // Enumerate unmerged files
            String unmerged = capture(worktreeDir, "git", "diff", "--name-only", "--diff-filter=U");
            List<String> files = Arrays.stream(unmerged.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            return MergeResult.conflict(files);
        } finally {
            gitGate.release();
        }
    }

    /** Hard-resets the worktree to the given SHA. Used for per-task rollback on give-up. */
    public void resetHard(File worktreeDir, String sha) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            run(worktreeDir, "git", "reset", "--hard", sha);
        } finally {
            gitGate.release();
        }
    }

    public boolean worktreeExists(String relativePath) {
        return Files.isDirectory(repoRoot.resolve(relativePath));
    }

    private void run(File cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IOException("Command failed (" + p.exitValue() + "): "
                    + String.join(" ", cmd) + "\n" + out);
        }
    }

    private String capture(File cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(false)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + err);
        }
        return out;
    }
}
