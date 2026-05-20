package io.bitken.shipsmooth.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class PackageRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void zipIsCreatedWithLauncherAndRuntime() throws IOException {
        // minimal jlink image structure
        Path fakeJlinkImage = tempDir.resolve("jlink-image");
        Files.createDirectories(fakeJlinkImage.resolve("bin"));
        Files.createDirectories(fakeJlinkImage.resolve("lib"));
        Files.writeString(fakeJlinkImage.resolve("bin/java"), "#!/bin/sh\necho fake-java");
        Files.writeString(fakeJlinkImage.resolve("bin/shipsmooth-tasks"), "#!/bin/sh\necho fake");

        Path outputDir = tempDir.resolve("dist");
        Files.createDirectories(outputDir);

        Path fakeJdkHome = tempDir.resolve("jdk");
        PackageRuntime pr = new PackageRuntime("linux-x64", fakeJdkHome, fakeJlinkImage, outputDir, "0.3.0");
        pr.run();

        Path zip = outputDir.resolve("shipsmooth-tasks-0.3.0-linux-x64.zip");
        assertTrue(Files.exists(zip), "zip file must be created");

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("bin/shipsmooth-tasks"), "launcher must be in zip");
            assertNotNull(zf.getEntry("runtime/bin/java"), "jlink java binary must be in zip");
        }
    }

    // Integration test: win32-x64 zip must use .cmd launcher (drives Task 2)
    @Test
    void windowsZipContainsCmdLauncher() throws IOException {
        Path fakeJdkHome = tempDir.resolve("jdk");
        Path fakeJlinkImage = tempDir.resolve("jlink-image");

        Files.createDirectories(fakeJlinkImage.resolve("bin"));
        Files.createDirectories(fakeJlinkImage.resolve("lib"));
        Files.writeString(fakeJlinkImage.resolve("bin/java.exe"), "fake");
        Files.writeString(fakeJlinkImage.resolve("bin/shipsmooth-tasks"), "fake");

        Path outputDir = tempDir.resolve("dist");
        Files.createDirectories(outputDir);

        PackageRuntime pr = new PackageRuntime("win32-x64", fakeJdkHome, fakeJlinkImage, outputDir, "0.3.0");
        pr.run();

        Path zip = outputDir.resolve("shipsmooth-tasks-0.3.0-win32-x64.zip");
        assertTrue(Files.exists(zip), "win32-x64 zip must be created");

        try (var zf = new java.util.zip.ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("bin/shipsmooth-tasks.cmd"), "windows zip must contain .cmd launcher");
            assertNull(zf.getEntry("bin/shipsmooth-tasks"), "windows zip must not contain POSIX launcher");
        }
    }

    @Test
    void windowsLauncherContainsVersion() throws IOException {
        Path fakeJdkHome = tempDir.resolve("jdk");
        Path fakeJlinkImage = tempDir.resolve("jlink-image");

        Files.createDirectories(fakeJlinkImage.resolve("bin"));
        Files.writeString(fakeJlinkImage.resolve("bin/shipsmooth-tasks"), "fake");

        Path outputDir = tempDir.resolve("dist");
        Files.createDirectories(outputDir);

        PackageRuntime pr = new PackageRuntime("win32-x64", fakeJdkHome, fakeJlinkImage, outputDir, "1.2.3");
        pr.run();

        try (var zf = new java.util.zip.ZipFile(outputDir.resolve("shipsmooth-tasks-1.2.3-win32-x64.zip").toFile())) {
            var entry = zf.getEntry("bin/shipsmooth-tasks.cmd");
            assertNotNull(entry);
            String content = new String(zf.getInputStream(entry).readAllBytes());
            assertTrue(content.contains("shipsmooth_v1.2.3"), "cmd launcher must embed version in SCC name");
        }
    }

    @Test
    void launcherContainsCorrectVersion() throws IOException {
        Path fakeJdkHome = tempDir.resolve("jdk");
        Path fakeJlinkImage = tempDir.resolve("jlink-image");

        Files.createDirectories(fakeJlinkImage.resolve("bin"));
        Files.createDirectories(fakeJlinkImage.resolve("lib"));
        Files.writeString(fakeJlinkImage.resolve("bin/java"), "#!/bin/sh");
        Files.writeString(fakeJlinkImage.resolve("bin/shipsmooth-tasks"), "#!/bin/sh");

        Path outputDir = tempDir.resolve("dist");
        Files.createDirectories(outputDir);

        PackageRuntime pr = new PackageRuntime("linux-x64", fakeJdkHome, fakeJlinkImage, outputDir, "0.3.0");
        pr.run();

        Path zip = outputDir.resolve("shipsmooth-tasks-0.3.0-linux-x64.zip");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entry = zf.getEntry("bin/shipsmooth-tasks");
            assertNotNull(entry);
            String launcherContent = new String(zf.getInputStream(entry).readAllBytes());
            assertTrue(launcherContent.contains("shipsmooth_v0.3.0"), "launcher must embed version in SCC name");
        }
    }
}
