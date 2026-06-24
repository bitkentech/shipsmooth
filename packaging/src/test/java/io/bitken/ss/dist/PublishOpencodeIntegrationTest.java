package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preamble integration test (plan-89): pins the end-to-end contract that decouples
 * the opencode npm publish from the rest of the release. Exercises the feature's two
 * guarantees through the public seams, without touching the live npm registry:
 *
 * <ol>
 *   <li>The default release does NOT attempt the opencode npm publish — a missing
 *       {@code @bitkentech} npm token can no longer strand the GitHub/Windows release.</li>
 *   <li>An "already published at this version" npm failure is idempotently swallowed,
 *       so the separate publish task can be safely re-run.</li>
 * </ol>
 *
 * Red until plan-89 Tasks 1 + 3 land.
 */
public class PublishOpencodeIntegrationTest {

    @Test
    void releaseDoesNotPublishOpencodeNpmByDefault() {
        // The gate that keeps npm out of the default release path. Default must be false:
        // the core release (GitHub + Windows) completes independent of npm auth.
        assertFalse(PublishRelease.PUBLISH_OPENCODE_NPM_DEFAULT,
                "default release must NOT attempt opencode npm publish");
    }

    @Test
    void standalonePublisherIsIdempotentOnAlreadyPublished() {
        // Re-running the separate publish task after a version is already on the registry
        // must be a no-op success, not a hard failure.
        String npm403 = "npm error code E403\n"
                + "npm error 403 cannot publish over the previously published versions: 0.3.27.";
        assertTrue(PublishOpencode.isAlreadyPublished(npm403),
                "npm 403 already-published output must classify as already-published");
    }

    @Test
    void standalonePublisherTreatsAuthFailureAsRealError() {
        // A 401 (no/invalid npm token) is a REAL failure — it must NOT be swallowed,
        // so the human running the separate task learns auth is missing.
        String npm401 = "npm error code E401\nnpm error 401 Unauthorized - PUT ...";
        assertFalse(PublishOpencode.isAlreadyPublished(npm401),
                "npm 401 auth failure must NOT be treated as already-published");
    }

    @Test
    void publishCommandTargetsAssembledPayloadDir() {
        // The publish command still operates on the assembled build-opencode/ payload dir.
        List<String> cmd = PublishOpencode.npmPublishCommand(
                java.nio.file.Path.of("/tmp/repo"));
        assertEquals(List.of("npm", "publish", "/tmp/repo/build-opencode"), cmd);
    }
}
