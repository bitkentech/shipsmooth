package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PublishOpencodeTest {

    // ---- npmPublishCommand: pure command builder targeting the payload dir ----

    @Test
    void npmPublishCommandTargetsBuildOpencodeDir() {
        List<String> cmd = PublishOpencode.npmPublishCommand(Path.of("/tmp/repo"));
        assertEquals(List.of("npm", "publish", "/tmp/repo/build-opencode"), cmd);
    }

    // ---- isAlreadyPublished: idempotency classifier (both directions) ----

    @Test
    void alreadyPublishedClassifiesNpm403CannotPublishOver() {
        String out = "npm error code E403\n"
                + "npm error 403 cannot publish over the previously published versions: 0.3.27.";
        assertTrue(PublishOpencode.isAlreadyPublished(out));
    }

    @Test
    void alreadyPublishedClassifiesEpublishConflict() {
        assertTrue(PublishOpencode.isAlreadyPublished("npm error EPUBLISHCONFLICT ..."));
    }

    @Test
    void alreadyPublishedIsCaseInsensitive() {
        assertTrue(PublishOpencode.isAlreadyPublished(
                "NPM ERROR 403 Cannot Publish Over The Previously Published versions"));
    }

    @Test
    void authFailureIsNotAlreadyPublished() {
        String out = "npm error code E401\nnpm error 401 Unauthorized - PUT ...";
        assertFalse(PublishOpencode.isAlreadyPublished(out),
                "401 auth failure must stay a hard error");
    }

    @Test
    void genericFailureIsNotAlreadyPublished() {
        assertFalse(PublishOpencode.isAlreadyPublished("npm error network ETIMEDOUT"));
    }

    @Test
    void nullOutputIsNotAlreadyPublished() {
        assertFalse(PublishOpencode.isAlreadyPublished(null));
    }

    // A bare E403 without the "previously published" reason must NOT be swallowed —
    // 403 can also mean "not authorized to publish this scope", which is a real error.
    @Test
    void bareForbiddenWithoutPreviouslyPublishedIsNotAlreadyPublished() {
        assertFalse(PublishOpencode.isAlreadyPublished(
                "npm error code E403\nnpm error 403 Forbidden - PUT (not authorized)"));
    }

    // ---- assembleOpencodeCommand: targets build-opencode/ with the explicit outputDir ----

    // plan-90: the assemble MUST pass -Pbuild.outputDir, else assembleOpencodeProd writes to
    // the dev dir while validate/publish read build-opencode/ — they'd disagree and validate
    // would fail "not assembled" right after a successful build.
    @Test
    void assembleCommandTargetsBuildOpencodeWithExplicitOutputDir() {
        List<String> cmd = PublishOpencode.assembleOpencodeCommand(Path.of("/tmp/repo"));
        assertTrue(cmd.contains("assembleOpencodeProd"), cmd.toString());
        assertTrue(cmd.contains("-Pbuild.outputDir=/tmp/repo/build-opencode"),
                "assemble must target build-opencode/ explicitly: " + cmd);
        assertTrue(cmd.get(0).endsWith("gradlew"), cmd.toString());
    }
}
