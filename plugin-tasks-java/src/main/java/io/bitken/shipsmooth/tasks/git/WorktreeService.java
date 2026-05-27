package io.bitken.shipsmooth.tasks.git;

import io.bitken.shipsmooth.tasks.workflow.ProcessRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Low-level git worktree operations.
 *
 * <p>All git commands are dispatched through the injected {@link ProcessRunner} so
 * there is one code path for process spawning regardless of the call site.
 * The {@link #gitGate} semaphore serialises concurrent git index writes.
 */
public class WorktreeService {

    private final Semaphore gitGate = new Semaphore(4);
    private final Path repoRoot;
    private final ProcessRunner processes;

    public WorktreeService(Path repoRoot, ProcessRunner processes) {
        this.repoRoot = repoRoot;
        this.processes = processes;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public String headSha() throws IOException, InterruptedException {
        return headSha(repoRoot.toFile());
    }

    /** HEAD SHA of the given worktree directory (use this when querying an integration worktree). */
    public String headSha(File worktreeDir) throws IOException, InterruptedException {
        return processes.capture(worktreeDir, "git", "rev-parse", "HEAD").trim();
    }

    /** Returns the HEAD SHA of the named branch, resolving it in the repo. */
    public String branchSha(String branch) throws IOException, InterruptedException {
        return processes.capture(repoRoot.toFile(), "git", "rev-parse", branch).trim();
    }

    public boolean branchExists(String branch) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            try {
                processes.capture(repoRoot.toFile(), "git", "rev-parse", "--verify", branch);
                return true;
            } catch (IOException e) {
                return false;
            }
        } finally {
            gitGate.release();
        }
    }

    public void deleteBranch(String branch) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            processes.run(repoRoot.toFile(), "git", "branch", "-D", branch);
        } finally {
            gitGate.release();
        }
    }

    public void addWorktree(String relativePath, String branch) throws IOException, InterruptedException {
        addWorktree(relativePath, branch, null);
    }

    /** When baseSha is non-blank, the worktree starts at that commit instead of HEAD. */
    public void addWorktree(String relativePath, String branch, String baseSha) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            if (baseSha != null && !baseSha.isBlank()) {
                processes.run(repoRoot.toFile(), "git", "worktree", "add", relativePath, "-b", branch, baseSha);
            } else {
                processes.run(repoRoot.toFile(), "git", "worktree", "add", relativePath, "-b", branch);
            }
        } finally {
            gitGate.release();
        }
    }

    /**
     * Remove worktree directory but keep the branch ref. Failures from the
     * underlying {@code git worktree remove} are swallowed — this is a cleanup
     * step and the caller does not have a recovery path. Use {@link #removeWorktreeStrict}
     * if you need the IOException to propagate.
     */
    public void removeWorktreeKeepBranch(String relativePath) throws IOException, InterruptedException {
        gitGate.acquireUninterruptibly();
        try {
            try {
                processes.run(repoRoot.toFile(), "git", "worktree", "remove", "--force", relativePath);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        } finally {
            gitGate.release();
        }
    }

    /** Remove worktree directory AND delete the branch. Best-effort; failures are swallowed. */
    public void removeWorktree(String relativePath, String branch) {
        gitGate.acquireUninterruptibly();
        try {
            try {
                processes.run(repoRoot.toFile(), "git", "worktree", "remove", "--force", relativePath);
            } catch (IOException ignored) {
                // best-effort cleanup
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                processes.run(repoRoot.toFile(), "git", "branch", "-D", branch);
            } catch (IOException ignored) {
                // best-effort cleanup
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
            processes.run(worktreeDir, "git", "add", "-A");
            String status = processes.capture(worktreeDir, "git", "status", "--porcelain").trim();
            if (status.isEmpty()) {
                return processes.capture(worktreeDir, "git", "rev-parse", "HEAD").trim();
            }
            processes.run(worktreeDir, "git", "commit", "-q", "-m", message);
            return processes.capture(worktreeDir, "git", "rev-parse", "HEAD").trim();
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
            processes.run(worktreeDir, "git", "add", "-A");
            return processes.capture(worktreeDir, "git", "diff", "--cached");
        } finally {
            gitGate.release();
        }
    }

    /** Creates a worktree from a named ref (branch name or tag), not necessarily HEAD. */
    public void addWorktreeAt(String relativePath, String branch, String baseRef)
            throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            processes.run(repoRoot.toFile(), "git", "worktree", "add", relativePath, "-b", branch, baseRef);
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
            try {
                processes.run(worktreeDir, "git", "merge", "--squash", branch);
                return MergeResult.success();
            } catch (IOException e) {
                // merge --squash exits non-zero on conflicts; enumerate unmerged files
            }
            String unmerged = processes.capture(worktreeDir, "git", "diff", "--name-only", "--diff-filter=U");
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
            processes.run(worktreeDir, "git", "reset", "--hard", sha);
        } finally {
            gitGate.release();
        }
    }

    public boolean worktreeExists(String relativePath) {
        return Files.isDirectory(repoRoot.resolve(relativePath));
    }

    // ── git facade methods used by WorkflowServiceImpl ─────────────────────────

    /** Returns the current branch name (--abbrev-ref HEAD). */
    public String currentBranch() throws IOException, InterruptedException {
        return processes.capture(repoRoot.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /** {@code git log --oneline {range}} output, e.g. range = "sha1..sha2". */
    public String logOneline(String range) throws IOException, InterruptedException {
        return processes.capture(repoRoot.toFile(), "git", "log", "--oneline", range);
    }

    /** {@code git log --oneline {range}} from a specific worktree directory. */
    public String logOneline(File worktreeDir, String range) throws IOException, InterruptedException {
        return processes.capture(worktreeDir, "git", "log", "--oneline", range);
    }

    /** Re-attaches the worktree for an existing branch (no {@code -b}). */
    public void attachWorktree(String relativePath, String branch) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            processes.run(repoRoot.toFile(), "git", "worktree", "add", relativePath, branch);
        } finally {
            gitGate.release();
        }
    }

    /**
     * Stage a single path and commit it with the given message.
     * No-ops if the file is unmodified.
     */
    public void commitFile(File worktreeDir, String filePath, String message) throws IOException, InterruptedException {
        gitGate.acquire();
        try {
            processes.run(worktreeDir, "git", "add", filePath);
            String status = processes.capture(worktreeDir, "git", "status", "--porcelain", filePath).trim();
            if (!status.isEmpty()) {
                processes.run(worktreeDir, "git", "commit", "-m", message);
            }
        } finally {
            gitGate.release();
        }
    }
}
