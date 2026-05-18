package io.bitken.shipsmooth.dist;

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
}