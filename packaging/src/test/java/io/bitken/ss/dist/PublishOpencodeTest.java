package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PublishOpencodeTest {

    @TempDir
    Path tempDir;

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

    // ---- run(): fail-fast when the payload was not assembled ----

    @Test
    void runFailsFastWhenPayloadNotAssembled() {
        // tempDir has no build-opencode/package.json
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PublishOpencode("9.9.9", tempDir).run());
        assertTrue(ex.getMessage().contains("not assembled"), ex.getMessage());
        assertTrue(ex.getMessage().contains("build-opencode"), ex.getMessage());
    }

    @Test
    void runFailsFastWhenPayloadDirExistsButNoManifest() throws IOException {
        // dir present but package.json missing — still not a publishable payload
        Files.createDirectories(tempDir.resolve("build-opencode"));
        assertThrows(IllegalStateException.class,
                () -> new PublishOpencode("9.9.9", tempDir).run());
    }
}
