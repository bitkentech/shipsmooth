package io.bitken.ss.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * The root directory of the git repository containing {@code startDir}.
 * Falls back to {@code startDir} when git is unavailable or not in a repo.
 */
class RepoRoot {

    private final Path root;

    RepoRoot(Path startDir) {
        this.root = resolve(startDir);
    }

    Path path() {
        return root;
    }

    private static Path resolve(Path startDir) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .directory(startDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                int exit = p.waitFor();
                if (exit == 0 && line != null && !line.isBlank()) {
                    return Path.of(line.trim());
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return startDir;
    }
}
