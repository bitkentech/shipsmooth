package io.bitken.ss.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RepoRootTest {

    @Test
    void resolvesRepoRootFromSubdirectory(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("myrepo");
        Files.createDirectories(repo);
        exec(repo, "git", "init", "-q");

        Path subdir = repo.resolve("a/b/c");
        Files.createDirectories(subdir);

        assertEquals(repo.toRealPath(), new RepoRoot(subdir).path().toRealPath());
    }

    @Test
    void resolvesRepoRootWhenCalledFromRepoRoot(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("myrepo");
        Files.createDirectories(repo);
        exec(repo, "git", "init", "-q");

        assertEquals(repo.toRealPath(), new RepoRoot(repo).path().toRealPath());
    }

    @Test
    void fallsBackToGivenDirWhenNotInGitRepo(@TempDir Path tmp) {
        assertEquals(tmp, new RepoRoot(tmp).path());
    }

    private static void exec(Path dir, String... cmd) throws IOException {
        try {
            new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectErrorStream(true).start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
