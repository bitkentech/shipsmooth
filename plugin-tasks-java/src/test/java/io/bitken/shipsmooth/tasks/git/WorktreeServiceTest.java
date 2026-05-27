package io.bitken.shipsmooth.tasks.git;

import io.bitken.shipsmooth.tasks.workflow.DefaultProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class WorktreeServiceTest {

    @TempDir
    Path tempDir;

    private WorktreeService svc;

    @BeforeEach
    void setUp() throws Exception {
        git("init", "-q");
        git("config", "user.email", "test@test.local");
        git("config", "user.name", "Test");
        Files.writeString(tempDir.resolve("seed.txt"), "seed");
        git("add", "seed.txt");
        git("commit", "-q", "-m", "init");
        svc = new WorktreeService(tempDir, new DefaultProcessRunner());
    }

    @Test
    void headSha_returnsNonEmpty() throws Exception {
        String sha = svc.headSha();
        assertFalse(sha.isBlank());
        assertEquals(40, sha.length());
    }

    @Test
    void addAndRemoveWorktreeKeepsBranch() throws Exception {
        svc.addWorktree(".agents/tasks/1", "agent-work/1");

        Path wt = tempDir.resolve(".agents/tasks/1");
        assertTrue(wt.toFile().isDirectory(), "worktree dir should exist");

        // Write a file and verify the worktree is functional
        Files.writeString(wt.resolve("work.txt"), "hello");
        String diff = svc.diff(wt.toFile());
        assertTrue(diff.contains("work.txt"), "diff should mention work.txt");

        String commitSha = svc.commitAll(wt.toFile(), "test commit");
        assertFalse(commitSha.isBlank());

        // removeWorktreeKeepBranch: dir gone, branch survives
        svc.removeWorktreeKeepBranch(".agents/tasks/1");
        assertFalse(wt.toFile().exists(), "worktree dir should be removed");

        Process p = new ProcessBuilder("git", "branch", "--list", "agent-work/1")
                .directory(tempDir.toFile()).start();
        String branchOut = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        assertTrue(branchOut.contains("agent-work/1"), "branch should still exist after removeWorktreeKeepBranch");
    }

    @Test
    void diffAndCommitAll_roundTrip() throws Exception {
        svc.addWorktree(".agents/tasks/2", "agent-work/2");
        Path wt = tempDir.resolve(".agents/tasks/2");

        Files.writeString(wt.resolve("out.txt"), "subagent output");

        String diff = svc.diff(wt.toFile());
        assertTrue(diff.contains("out.txt"));
        assertTrue(diff.contains("subagent output"));

        String sha = svc.commitAll(wt.toFile(), "agent commit");
        assertEquals(40, sha.length());

        // Second commitAll with no changes returns same sha
        String sha2 = svc.commitAll(wt.toFile(), "agent commit 2");
        assertEquals(sha, sha2);

        svc.removeWorktreeKeepBranch(".agents/tasks/2");
    }

    @Test
    void worktreeExists_reflectsState() throws Exception {
        assertFalse(svc.worktreeExists(".agents/tasks/3"));
        svc.addWorktree(".agents/tasks/3", "agent-work/3");
        assertTrue(svc.worktreeExists(".agents/tasks/3"));
        svc.removeWorktreeKeepBranch(".agents/tasks/3");
        assertFalse(svc.worktreeExists(".agents/tasks/3"));
    }

    private void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(tempDir.toFile())
                .redirectErrorStream(true).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new RuntimeException("timeout"); }
        if (p.exitValue() != 0) throw new RuntimeException("git failed: " + new String(p.getInputStream().readAllBytes()));
    }
}
