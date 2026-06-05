package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PublishReleaseTest {

    @TempDir
    Path tempDir;

    @Test
    void runCommandCapturesToString() throws IOException, InterruptedException {
        String output = PublishRelease.runCommand(List.of("echo", "hello"), tempDir);
        assertEquals("hello", output.strip());
    }

    @Test
    void runCommandThrowsOnNonZeroExit() {
        assertThrows(IOException.class, () ->
            PublishRelease.runCommand(List.of("false"), tempDir)
        );
    }

    @Test
    void runCommandUsesWorkingDirectory() throws IOException, InterruptedException {
        String output = PublishRelease.runCommand(List.of("pwd"), tempDir);
        assertEquals(tempDir.toRealPath().toString(), output.strip());
    }

    @Test
    void assertCleanWorkingTreeFailsOnDirtyRepo() throws IOException, InterruptedException {
        PublishRelease.runCommand(List.of("git", "init"), tempDir);
        PublishRelease.runCommand(List.of("git", "config", "user.email", "test@test.com"), tempDir);
        PublishRelease.runCommand(List.of("git", "config", "user.name", "Test"), tempDir);
        Files.writeString(tempDir.resolve("README.md"), "hello");
        PublishRelease.runCommand(List.of("git", "add", "README.md"), tempDir);
        PublishRelease.runCommand(List.of("git", "commit", "-m", "init"), tempDir);
        // modify a tracked file without committing
        Files.writeString(tempDir.resolve("README.md"), "modified");

        assertThrows(IllegalStateException.class, () ->
            PublishRelease.assertCleanWorkingTree(tempDir)
        );
    }

    @Test
    void assertCleanWorkingTreePassesOnCleanRepo() throws IOException, InterruptedException {
        PublishRelease.runCommand(List.of("git", "init"), tempDir);
        PublishRelease.runCommand(List.of("git", "config", "user.email", "test@test.com"), tempDir);
        PublishRelease.runCommand(List.of("git", "config", "user.name", "Test"), tempDir);
        Files.writeString(tempDir.resolve("README.md"), "hello");
        PublishRelease.runCommand(List.of("git", "add", "README.md"), tempDir);
        PublishRelease.runCommand(List.of("git", "commit", "-m", "init"), tempDir);

        assertDoesNotThrow(() -> PublishRelease.assertCleanWorkingTree(tempDir));
    }

    @Test
    void validateBuildOutputThrowsOnPlaceholderLeak() throws IOException {
        Path claudePlugin = tempDir.resolve(".claude-plugin");
        Files.createDirectories(claudePlugin);
        Files.writeString(claudePlugin.resolve("plugin.json"), """
                {"name":"${plugin.name}","version":"0.3.8","description":"desc"}
                """);
        Files.writeString(claudePlugin.resolve("marketplace.json"), """
                {"name":"shipsmooth","plugins":[{"name":"shipsmooth","description":"desc"}]}
                """);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PublishRelease.validateBuildOutput(tempDir));
        assertTrue(ex.getMessage().contains("plugin.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("${plugin.name}"), ex.getMessage());
    }

    @Test
    void skipValidationSuppressesPlaceholderLeakError() throws IOException {
        Path claudePlugin = tempDir.resolve(".claude-plugin");
        Files.createDirectories(claudePlugin);
        Files.writeString(claudePlugin.resolve("plugin.json"), """
                {"name":"${plugin.name}","version":"0.3.8","description":"desc"}
                """);
        Files.writeString(claudePlugin.resolve("marketplace.json"), """
                {"name":"shipsmooth","plugins":[{"name":"shipsmooth","description":"desc"}]}
                """);

        // must not throw even though plugin.json has a placeholder leak
        assertDoesNotThrow(() -> PublishRelease.maybeValidateBuildOutput(tempDir, true));
    }

    // Guards against re-introducing the pre-jlink Node-distribution payload.
    // The release once copied build/scripts and build/package.json, which the
    // restructured build no longer emits — causing the v0.3.14 release to fail.
    @Test
    void shippedBuildSubpathsExcludeObsoleteNodeArtifacts() {
        assertEquals(List.of(".claude-plugin", "hooks", "dist", "skills"),
                PublishRelease.SHIPPED_BUILD_SUBPATHS);
        assertFalse(PublishRelease.SHIPPED_BUILD_SUBPATHS.contains("scripts"),
                "build/scripts is no longer produced by the jlink build");
        assertFalse(PublishRelease.SHIPPED_BUILD_SUBPATHS.contains("package.json"),
                "build/package.json is no longer produced by the jlink build");
    }

    @Test
    void validateBuildOutputPassesOnCleanManifests() throws IOException {
        Path claudePlugin = tempDir.resolve(".claude-plugin");
        Files.createDirectories(claudePlugin);
        Files.writeString(claudePlugin.resolve("plugin.json"), """
                {"name":"shipsmooth","version":"0.3.8","description":"Agent coding workflow"}
                """);
        Files.writeString(claudePlugin.resolve("marketplace.json"), """
                {"name":"shipsmooth","plugins":[{"name":"shipsmooth","description":"Agent coding workflow"}]}
                """);

        assertDoesNotThrow(() -> PublishRelease.validateBuildOutput(tempDir));
    }
}