package io.bitken.ss.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RepoRootResolverTest {

    @Test
    void resolvesRepoRootFromSubdirectory(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("myrepo");
        Files.createDirectories(repo);
        run(repo, "git", "init", "-q");

        Path subdir = repo.resolve("a/b/c");
        Files.createDirectories(subdir);

        Path resolved = RepoRootResolver.resolve(subdir);
        assertEquals(repo.toRealPath(), resolved.toRealPath());
    }

    @Test
    void resolvesRepoRootWhenCalledFromRepoRoot(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("myrepo");
        Files.createDirectories(repo);
        run(repo, "git", "init", "-q");

        Path resolved = RepoRootResolver.resolve(repo);
        assertEquals(repo.toRealPath(), resolved.toRealPath());
    }

    @Test
    void fallsBackToGivenDirWhenNotInGitRepo(@TempDir Path tmp) {
        Path resolved = RepoRootResolver.resolve(tmp);
        assertEquals(tmp, resolved);
    }

    private static void run(Path dir, String... cmd) throws IOException {
        try {
            new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
