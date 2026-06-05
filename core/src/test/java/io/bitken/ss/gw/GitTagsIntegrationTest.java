package io.bitken.ss.gw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link GitTags} against a real temporary git
 * repository. The test JVM's working directory is the module directory, NOT the
 * temp repo — so every git command issued by {@code GitTags} only succeeds if
 * the gateway runs git in its configured {@code workDir}. This reproduces the
 * live failure (plan 70 Defect B) where {@code GitTags} ran git in the inherited
 * CWD and every tag operation exited non-zero.
 *
 * <p>It also pins the first-version computation: with no tags, the first plan
 * version must be {@code v1}, not {@code v2}.
 */
public class GitTagsIntegrationTest {

    @TempDir
    Path repoDir;

    private GitTags gitTags;

    @BeforeEach
    void initRepo() throws Exception {
        git("init");
        git("config", "user.email", "test@test.com");
        git("config", "user.name", "Test");
        Files.writeString(repoDir.resolve("README.md"), "init");
        git("add", ".");
        git("commit", "-m", "init");
        gitTags = new GitTags(repoDir);
    }

    @Test
    void createsVersionTagWhenProcessCwdIsNotRepoRoot() {
        // The JVM CWD is not repoDir; this only passes if GitTags runs git in workDir.
        assertTrue(gitTags.createTag("plan-70-v1"), "createTag must succeed from a foreign CWD");
        assertTrue(gitTags.tagExists("plan-70-v1"), "the tag must actually exist after creation");
    }

    @Test
    void firstPlanVersionIsV1NotV2() {
        assertEquals("plan-70-v1", gitTags.nextPlanVersion(70),
                "the first version of a plan with no tags must be v1");
    }

    @Test
    void nextPlanVersionIncrementsHighestExistingTag() throws Exception {
        git("tag", "plan-70-v1");
        git("tag", "plan-70-v2");
        assertEquals("plan-70-v3", gitTags.nextPlanVersion(70),
                "next version after v2 must be v3");
    }

    @Test
    void getPlanVersionReturnsDefaultV1WhenNoTags() {
        assertEquals("plan-70-v1", gitTags.getPlanVersion(70));
    }

    @Test
    void getPlanVersionReturnsHighestExistingTag() throws Exception {
        git("tag", "plan-70-v1");
        git("tag", "plan-70-v2");
        assertEquals("plan-70-v2", gitTags.getPlanVersion(70));
    }

    @Test
    void tagExistsReturnsFalseWhenAbsent() {
        assertFalse(gitTags.tagExists("plan-70-v9"));
    }

    @Test
    void createTagReturnsFalseWhenTagAlreadyExists() {
        assertTrue(gitTags.createTag("plan-70-v1"));
        assertFalse(gitTags.createTag("plan-70-v1"),
                "re-creating an existing tag must fail (git exits non-zero)");
    }

    @Test
    void operationsReturnSafeDefaultsWhenWorkDirIsNotAGitRepo(@TempDir Path nonRepo) {
        GitTags orphan = new GitTags(nonRepo);
        assertEquals("plan-70-v1", orphan.getPlanVersion(70), "default version when git fails");
        assertEquals("plan-70-v1", orphan.nextPlanVersion(70), "first version when git fails");
        assertFalse(orphan.tagExists("plan-70-v1"), "no tag when git fails");
        assertFalse(orphan.createTag("plan-70-v1"), "createTag fails when not a repo");
    }

    private void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile()).start();
        int exit = p.waitFor();
        if (exit != 0) throw new RuntimeException("git " + args[0] + " failed with exit " + exit);
    }
}
