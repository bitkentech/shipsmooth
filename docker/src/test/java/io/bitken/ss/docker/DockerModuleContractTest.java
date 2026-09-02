package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bitken.ss.docker.ResolveVersions.Versions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Preamble integration test (plan-113). Pins the end-to-end contract of folding the
 * {@code shipsmooth-claude} image build into this repo as the {@code :docker} module:
 *
 * <ol>
 *   <li>The tooling compiles and its tests run <em>as the {@code :docker} module</em>
 *       — this class existing and executing under {@code ./gradlew :docker:test} is
 *       the proof (plan-113 Task 1).</li>
 *   <li>The shipsmooth version is supplied by the caller with <b>no hidden default</b>
 *       — {@link BuildAndPushImage#main} refuses to run without it, and whatever the
 *       caller passes flows verbatim into the image. The Gradle wiring feeds
 *       {@code plugin.version} into that seam (plan-113 Task 2); nothing in the Java
 *       hardcodes a version.</li>
 * </ol>
 *
 * Red until Task 1 creates the {@code :docker} module ({@code ./gradlew :docker:test}
 * fails with "Project 'docker' not found" / no such classes).
 */
class DockerModuleContractTest {

    @Test
    void buildRefusesToRunWithoutAnExplicitShipsmoothVersion() {
        // args: <mode> <repo> <shipsmoothVersion> [tag] — fewer than 3 must fail loudly,
        // so a missing plugin.version can never silently bake a blank/placeholder label.
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildAndPushImage.main(new String[] {"build", "bitkentech/shipsmooth-claude"}));
    }

    @Test
    void theCallerSuppliedShipsmoothVersionFlowsVerbatimIntoTheImage() {
        Versions versions = new Versions("9.9.9-claude", "1.2.3-plan113");

        ImagePlan plan = ImagePlan.forStandardTags(
                "bitkentech/shipsmooth-claude", versions, ".", "Dockerfile", "deadbeef", "2026-09-02T00:00:00Z");

        // The compound tag and the build-arg both carry exactly what the caller passed.
        assertTrue(
                plan.tags().stream().anyMatch(t -> t.endsWith(":claude-9.9.9-claude-ss-1.2.3-plan113")),
                () -> "compound tag must embed the caller's shipsmooth version, got " + plan.tags());
        assertConsecutive(plan.buildArgv(), "--build-arg", "SHIPSMOOTH_VERSION=1.2.3-plan113");
    }

    private static void assertConsecutive(List<String> argv, String a, String b) {
        for (int i = 0; i < argv.size() - 1; i++) {
            if (argv.get(i).equals(a) && argv.get(i + 1).equals(b)) {
                return;
            }
        }
        assertTrue(false, "expected consecutive [" + a + ", " + b + "] in " + argv);
    }
}
