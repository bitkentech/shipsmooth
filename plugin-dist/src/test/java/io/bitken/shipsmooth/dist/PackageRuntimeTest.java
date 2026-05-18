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
        Path fakeJdkHome = tempDir.resolve("jdk");
        Path fakeJlinkImage = tempDir.resolve("jlink-image");

        // minimal jlink image structure
        Files.createDirectories(fakeJlinkImage.resolve("bin"));
        Files.createDirectories(fakeJlinkImage.resolve("lib"));
        Files.writeString(fakeJlinkImage.resolve("bin/java"), "#!/bin/sh\necho fake-java");
        Files.writeString(fakeJlinkImage.resolve("bin/shipsmooth-tasks"), "#!/bin/sh\necho fake");

        Path outputDir = tempDir.resolve("dist");
        Files.createDirectories(outputDir);

        PackageRuntime pr = new PackageRuntime("linux-x64", fakeJdkHome, fakeJlinkImage, outputDir, "0.3.0");
        pr.run();

        Path zip = outputDir.resolve("shipsmooth-tasks-0.3.0-linux-x64.zip");
        assertTrue(Files.exists(zip), "zip file must be created");

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("bin/shipsmooth-tasks"), "launcher must be in zip");
            assertNotNull(zf.getEntry("runtime/bin/java"), "jlink java binary must be in zip");
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
