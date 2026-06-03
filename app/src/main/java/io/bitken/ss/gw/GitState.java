package io.bitken.ss.gw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only git-state queries used by plan preflight, branch, and resume.
 * All methods shell out to git in the given working directory.
 */
public class GitState {

    private final Path workDir;

    public GitState(Path workDir) {
        this.workDir = workDir;
    }

    /** True when the working tree has no uncommitted changes. */
    public boolean isClean() {
        try {
            List<String> lines = run("git", "status", "--porcelain");
            return lines.isEmpty();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Current branch name, or empty string if detached/unavailable. */
    public String currentBranch() {
        try {
            List<String> lines = run("git", "rev-parse", "--abbrev-ref", "HEAD");
            return lines.isEmpty() ? "" : lines.get(0).trim();
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /**
     * True when the current branch has a remote upstream and HEAD is not ahead of it
     * (i.e., all commits have been pushed).
     */
    public boolean isBranchPushedAndNotAhead() {
        try {
            List<String> upstream = run("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
            if (upstream.isEmpty() || upstream.get(0).trim().startsWith("fatal")) return false;
            List<String> ahead = run("git", "rev-list", "--count", "@{u}..HEAD");
            if (ahead.isEmpty()) return false;
            return "0".equals(ahead.get(0).trim());
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** True when the tag exists in the local repository. */
    public boolean tagExistsLocally(String tag) {
        try {
            List<String> lines = run("git", "tag", "-l", tag);
            return !lines.isEmpty() && !lines.get(0).isBlank();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** True when the tag exists on the remote (uses git ls-remote). */
    public boolean tagExistsOnRemote(String tag) {
        try {
            List<String> lines = run("git", "ls-remote", "--tags", "origin", tag);
            return !lines.isEmpty();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** True when a local branch with this name already exists. */
    public boolean branchExists(String branchName) {
        try {
            List<String> lines = run("git", "branch", "--list", branchName);
            return !lines.isEmpty() && !lines.get(0).isBlank();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Creates a local branch at HEAD. Returns true on success. */
    public boolean createBranch(String branchName) {
        try {
            Process p = new ProcessBuilder("git", "checkout", "-b", branchName)
                    .directory(workDir.toFile())
                    .start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Returns the output of git worktree list. */
    public List<String> worktreeList() {
        try {
            return run("git", "worktree", "list");
        } catch (IOException | InterruptedException e) {
            return List.of();
        }
    }

    private List<String> run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        p.waitFor();
        return lines;
    }
}
