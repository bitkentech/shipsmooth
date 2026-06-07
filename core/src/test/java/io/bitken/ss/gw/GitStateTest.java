package io.bitken.ss.gw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitState using a real temporary git repository.
 */
public class GitStateTest {

    @TempDir
    Path repoDir;

    private GitState gitState;

    @BeforeEach
    void initRepo() throws Exception {
        git("init");
        git("config", "user.email", "test@test.com");
        git("config", "user.name", "Test");
        // Need at least one commit for branch/tag operations to work
        Files.writeString(repoDir.resolve("README.md"), "init");
        git("add", ".");
        git("commit", "-m", "init");
        gitState = new GitState(repoDir);
    }

    @Test
    void isCleanOnFreshRepo() {
        assertTrue(gitState.isClean());
    }

    @Test
    void isCleanReturnsFalseWithUntrackedFile() throws IOException {
        Files.writeString(repoDir.resolve("dirty.txt"), "change");
        assertFalse(gitState.isClean());
    }

    @Test
    void isCleanReturnsFalseWithModifiedTrackedFile() throws Exception {
        Files.writeString(repoDir.resolve("README.md"), "modified");
        assertFalse(gitState.isClean());
    }

    @Test
    void currentBranchReturnsName() {
        String branch = gitState.currentBranch();
        assertFalse(branch.isEmpty());
    }

    @Test
    void tagExistsLocallyAfterCreation() throws Exception {
        git("tag", "plan-7-v1");
        assertTrue(gitState.tagExistsLocally("plan-7-v1"));
    }

    @Test
    void tagExistsLocallyReturnsFalseWhenAbsent() {
        assertFalse(gitState.tagExistsLocally("plan-7-v1"));
    }

    @Test
    void branchExistsAfterCreation() throws Exception {
        git("branch", "t/pb-99-my-feature");
        assertTrue(gitState.branchExists("t/pb-99-my-feature"));
    }

    @Test
    void branchExistsReturnsFalseForNonExistentBranch() {
        assertFalse(gitState.branchExists("t/pb-99-no-such-branch"));
    }

    @Test
    void createBranchSucceedsAndSwitchesBranch() {
        boolean created = gitState.createBranch("t/pb-99-new-branch");
        assertTrue(created);
        assertEquals("t/pb-99-new-branch", gitState.currentBranch());
    }

    @Test
    void createBranchFailsIfAlreadyExists() throws Exception {
        git("branch", "t/pb-99-existing");
        boolean created = gitState.createBranch("t/pb-99-existing");
        assertFalse(created);
    }

    @Test
    void createBranchFailureSurfacesGitStderr() throws Exception {
        git("branch", "t/pb-99-existing");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            assertFalse(gitState.createBranch("t/pb-99-existing"));
        } finally {
            System.setErr(originalErr);
        }
        String err = captured.toString();
        assertTrue(err.contains("already exists"),
                "git's stderr should be surfaced on failure, got: " + err);
    }

    @Test
    void createBranchReturnsFalseAndReportsWhenGitCannotRun() {
        // Working dir does not exist -> ProcessBuilder.start() throws IOException.
        GitState broken = new GitState(repoDir.resolve("does-not-exist"));
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            assertFalse(broken.createBranch("t/pb-99-anything"));
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.toString().contains("could not run"),
                "a git that cannot be launched should report why, got: " + captured);
    }

    @Test
    void worktreeListReturnsAtLeastMainWorktree() {
        var list = gitState.worktreeList();
        assertFalse(list.isEmpty());
        assertTrue(list.stream().anyMatch(l -> l.contains(repoDir.toString())));
    }

    @Test
    void isBranchPushedReturnsFalseWithNoUpstream() {
        assertFalse(gitState.isBranchPushedAndNotAhead());
    }

    @Test
    void tagExistsOnRemoteReturnsFalseWithNoRemote() {
        assertFalse(gitState.tagExistsOnRemote("plan-7-v1"));
    }

    private void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .directory(repoDir.toFile())
                .start();
        int exit = p.waitFor();
        if (exit != 0) throw new RuntimeException("git " + args[0] + " failed with exit " + exit);
    }
}
