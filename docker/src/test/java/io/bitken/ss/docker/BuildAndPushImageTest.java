package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bitken.ss.docker.ResolveVersions.Versions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildAndPushImageTest {

    private static final Versions V = new Versions("2.1.236", "0.3.36");

    /** Records the argvs it is asked to run and returns a scripted exit code. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        int exitCode = 0;

        @Override
        public int run(List<String> argv) {
            calls.add(argv);
            return exitCode;
        }

        @Override
        public String capture(List<String> argv) {
            calls.add(argv);
            return "";
        }
    }

    @Test
    void hasDockerHubAuthAcceptsAnAuthsEntry() {
        String config = "{\"auths\":{\"https://index.docker.io/v1/\":{\"auth\":\"deadbeef\"}}}";
        assertTrue(BuildAndPushImage.hasDockerHubAuth(config));
    }

    @Test
    void hasDockerHubAuthAcceptsACredentialHelper() {
        assertTrue(BuildAndPushImage.hasDockerHubAuth("{\"credsStore\":\"desktop\"}"));
    }

    @Test
    void hasDockerHubAuthRejectsAnEmptyOrUnrelatedConfig() {
        assertFalse(BuildAndPushImage.hasDockerHubAuth("{}"));
        assertFalse(BuildAndPushImage.hasDockerHubAuth("{\"auths\":{\"ghcr.io\":{}}}"));
    }

    @Test
    void buildImageRunsExactlyTheBuildArgv() {
        FakeRunner runner = new FakeRunner();
        ImagePlan plan = ImagePlan.forExplicitTag("r:smoke", V, ".", "Dockerfile", "abc", "2026-09-01T00:00:00Z");

        BuildAndPushImage.buildImage(plan, runner);

        assertEquals(1, runner.calls.size());
        assertEquals(plan.buildArgv(), runner.calls.get(0));
    }

    @Test
    void buildImageThrowsOnNonZeroExit() {
        FakeRunner runner = new FakeRunner();
        runner.exitCode = 2;
        ImagePlan plan = ImagePlan.forExplicitTag("r:smoke", V, ".", "Dockerfile", "abc", "2026-09-01T00:00:00Z");

        assertThrows(IllegalStateException.class, () -> BuildAndPushImage.buildImage(plan, runner));
    }

    @Test
    void pushRunsOneCommandPerTagInOrder() {
        FakeRunner runner = new FakeRunner();
        ImagePlan plan = ImagePlan.forStandardTags(
                "bitkentech/shipsmooth-claude", V, ".", "Dockerfile", "abc", "2026-09-01T00:00:00Z");

        BuildAndPushImage.push(plan, runner);

        assertEquals(plan.pushArgvs(), runner.calls);
    }

    @Test
    void overviewMentionsBothComponentVersionsAndThePullCommand() {
        String md = BuildAndPushImage.renderOverview("bitkentech/shipsmooth-claude", V);
        assertTrue(md.contains("2.1.236"), md);
        assertTrue(md.contains("0.3.36"), md);
        assertTrue(md.contains("docker pull bitkentech/shipsmooth-claude"), md);
    }
}
