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

    // ---- Task 26: Gradle-native release path (de-Maven) ----

    @Test
    void bumpVersionRewritesGradlePropertiesPluginVersion() throws IOException {
        Files.writeString(tempDir.resolve("gradle.properties"), """
                org.gradle.java.installations.auto-download=false
                plugin.version=0.3.14
                experimental.enabled=true
                """);

        boolean changed = PublishRelease.bumpVersionInGradleProperties(tempDir, "0.4.0");

        assertTrue(changed, "version differed, should report changed");
        String after = Files.readString(tempDir.resolve("gradle.properties"));
        assertTrue(after.contains("plugin.version=0.4.0"), after);
        assertFalse(after.contains("plugin.version=0.3.14"), after);
        // other lines untouched
        assertTrue(after.contains("experimental.enabled=true"), after);
        assertTrue(after.contains("org.gradle.java.installations.auto-download=false"), after);
    }

    @Test
    void bumpVersionIsNoOpWhenAlreadyAtTarget() throws IOException {
        Files.writeString(tempDir.resolve("gradle.properties"), "plugin.version=0.4.0\n");

        boolean changed = PublishRelease.bumpVersionInGradleProperties(tempDir, "0.4.0");

        assertFalse(changed, "already at target version, nothing to change");
        assertEquals("plugin.version=0.4.0\n", Files.readString(tempDir.resolve("gradle.properties")));
    }

    // The whole point of Task 26: NO mvn anywhere in the build commands.
    @Test
    void buildCommandsContainNoMaven() {
        List<List<String>> all = List.of(
                PublishRelease.jlinkBuildCommand(tempDir),
                PublishRelease.assembleProdCommand(tempDir),
                PublishRelease.assembleWindowsCommand(tempDir));
        for (List<String> cmd : all) {
            assertFalse(cmd.contains("mvn"), "command must not invoke mvn: " + cmd);
            assertTrue(cmd.get(0).endsWith("gradlew"), "command must invoke gradlew: " + cmd);
        }
    }

    @Test
    void jlinkBuildCommandBuildsAllFourPlatformsWithoutFlag() {
        List<String> cmd = PublishRelease.jlinkBuildCommand(tempDir);
        // plan-74: -PjlinkBuild gate removed; the four platform tasks stay listed.
        assertFalse(cmd.contains("-PjlinkBuild"), cmd.toString());
        assertTrue(cmd.contains(":cli:jlinkImage_linux-x64"), cmd.toString());
        assertTrue(cmd.contains(":cli:jlinkImage_darwin-x64"), cmd.toString());
        assertTrue(cmd.contains(":cli:jlinkImage_darwin-arm64"), cmd.toString());
        assertTrue(cmd.contains(":cli:jlinkImage_windows-x64"), cmd.toString());
    }

    @Test
    void assembleProdCommandTargetsClaudeProdIntoBuild() {
        List<String> cmd = PublishRelease.assembleProdCommand(tempDir);
        assertTrue(cmd.contains("assembleClaudeProd"), cmd.toString());
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("-Pbuild.outputDir=")
                && a.endsWith("build")), cmd.toString());
    }

    @Test
    void assembleWindowsCommandTargetsWindowsIntoBuildWindows() {
        List<String> cmd = PublishRelease.assembleWindowsCommand(tempDir);
        assertTrue(cmd.contains("assembleWindows"), cmd.toString());
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("-Pbuild.outputDir=")
                && a.endsWith("build-windows")), cmd.toString());
    }

    // PackageRuntime must read the jlink image from the GRADLE output dir
    // (cli/build/...), not the old Maven location (cli/target/...). Reading the
    // Maven path packaged a stale image, or none on a clean tree.
    @Test
    void jlinkImagePathPointsAtGradleBuildDir() {
        Path p = PublishRelease.jlinkImagePath(tempDir, "linux-x64");
        assertEquals(tempDir.resolve("cli/build/jlink-image-linux-x64"), p);
        assertFalse(p.toString().contains("cli/target"),
                "must not read the Maven jlink image path: " + p);
    }

    @Test
    void jlinkImagePathUsesPlatformSuffix() {
        assertTrue(PublishRelease.jlinkImagePath(tempDir, "windows-x64").toString()
                .endsWith("cli/build/jlink-image-windows-x64"));
        assertTrue(PublishRelease.jlinkImagePath(tempDir, "darwin-arm64").toString()
                .endsWith("cli/build/jlink-image-darwin-arm64"));
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