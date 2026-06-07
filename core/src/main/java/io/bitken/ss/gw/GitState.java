package io.bitken.ss.gw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only git-state queries and local write ops (branch creation) used by
 * plan preflight, branch, and resume commands. All methods shell out to git
 * in the configured working directory.
 */
public class GitState {

    private final Path workDir;

    public GitState(Path workDir) {
        this.workDir = workDir;
    }

    /** True when the working tree has no uncommitted changes. */
    public boolean isClean() {
        return runLines("git", "status", "--porcelain").isEmpty();
    }

    /** Current branch name, or empty string if detached/unavailable. */
    public String currentBranch() {
        List<String> lines = runLines("git", "rev-parse", "--abbrev-ref", "HEAD");
        return lines.isEmpty() ? "" : lines.get(0).trim();
    }

    /**
     * True when the current branch has a remote upstream and HEAD is not
     * ahead of it (i.e., all local commits have been pushed).
     */
    public boolean isBranchPushedAndNotAhead() {
        if (runExitCode("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}") != 0) {
            return false;
        }
        List<String> ahead = runLines("git", "rev-list", "--count", "@{u}..HEAD");
        return !ahead.isEmpty() && "0".equals(ahead.get(0).trim());
    }

    /** True when the tag exists in the local repository. */
    public boolean tagExistsLocally(String tag) {
        List<String> lines = runLines("git", "tag", "-l", tag);
        return !lines.isEmpty() && !lines.get(0).isBlank();
    }

    /** True when the tag exists on the remote (uses git ls-remote). */
    public boolean tagExistsOnRemote(String tag) {
        return !runLines("git", "ls-remote", "--tags", "origin", tag).isEmpty();
    }

    /** True when a local branch with this name already exists. */
    public boolean branchExists(String branchName) {
        List<String> lines = runLines("git", "branch", "--list", branchName);
        return !lines.isEmpty() && !lines.get(0).isBlank();
    }

    /** Creates a local branch at HEAD. Returns true on success. */
    public boolean createBranch(String branchName) {
        return runExitCode("git", "checkout", "-b", branchName) == 0;
    }

    /** Returns the output lines of git worktree list. */
    public List<String> worktreeList() {
        return runLines("git", "worktree", "list");
    }

    private List<String> runLines(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return r.lines().toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    private int runExitCode(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0 && !output.isBlank()) {
                System.err.println(String.join(" ", cmd) + " failed (exit " + exit + "): " + output.strip());
            }
            return exit;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println(String.join(" ", cmd) + " could not run: " + e.getMessage());
            return -1;
        }
    }
}
