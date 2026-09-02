package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bitken.ss.docker.ResolveVersions.Versions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImagePlanTest {

    private static final Versions V = new Versions("2.1.236", "0.3.36");
    private static final String REPO = "bitkentech/shipsmooth-claude";

    @Test
    void standardTagsAreLatestDatedAndCompound() {
        List<String> tags = ImagePlan.standardTags(REPO, V, "2026-09-01");
        assertEquals(
                List.of(
                        "bitkentech/shipsmooth-claude:latest",
                        "bitkentech/shipsmooth-claude:2026-09-01",
                        "bitkentech/shipsmooth-claude:claude-2.1.236-ss-0.3.36"),
                tags);
    }

    @Test
    void explicitTagOverridesTheStandardSet() {
        ImagePlan plan = ImagePlan.forExplicitTag("myrepo:smoke", V, ".", "Dockerfile", "abc123", "2026-09-01T00:00:00Z");
        assertEquals(List.of("myrepo:smoke"), plan.tags());
    }

    @Test
    void buildArgvPinsEveryBuildArgAndPassesTheSecret() {
        ImagePlan plan = ImagePlan.forExplicitTag("myrepo:smoke", V, "ctx", "ctx/Dockerfile", "abc123", "2026-09-01T00:00:00Z");
        List<String> argv = plan.buildArgv();

        assertEquals("docker", argv.get(0));
        assertEquals("build", argv.get(1));
        assertContainsSequence(argv, "--secret", "id=claude_api_key,env=CLAUDE_API_KEY");
        assertContainsSequence(argv, "--build-arg", "CLAUDE_VERSION=2.1.236");
        assertContainsSequence(argv, "--build-arg", "SHIPSMOOTH_VERSION=0.3.36");
        assertContainsSequence(argv, "--build-arg", "IMAGE_VERSION=claude-2.1.236-ss-0.3.36");
        assertContainsSequence(argv, "--build-arg", "VCS_REF=abc123");
        assertContainsSequence(argv, "--build-arg", "BUILD_DATE=2026-09-01T00:00:00Z");
        assertContainsSequence(argv, "-t", "myrepo:smoke");
        assertContainsSequence(argv, "-f", "ctx/Dockerfile");
        assertEquals("ctx", argv.get(argv.size() - 1), "context dir is the last arg");
    }

    @Test
    void pushArgvIsOnePerTag() {
        ImagePlan plan = ImagePlan.forStandardTags(REPO, V, ".", "Dockerfile", "abc123", "2026-09-01T00:00:00Z");
        assertEquals(
                List.of(
                        List.of("docker", "push", "bitkentech/shipsmooth-claude:latest"),
                        List.of("docker", "push", "bitkentech/shipsmooth-claude:2026-09-01"),
                        List.of("docker", "push", "bitkentech/shipsmooth-claude:claude-2.1.236-ss-0.3.36")),
                plan.pushArgvs());
    }

    private static void assertContainsSequence(List<String> haystack, String a, String b) {
        for (int i = 0; i < haystack.size() - 1; i++) {
            if (haystack.get(i).equals(a) && haystack.get(i + 1).equals(b)) {
                return;
            }
        }
        assertTrue(false, "expected consecutive [" + a + ", " + b + "] in " + haystack);
    }
}
