package io.bitken.ss.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Resolves the git repo root by running {@code git rev-parse --show-toplevel}
 * from the given directory. Falls back to the given directory if git is
 * unavailable or the directory is not inside a git repo.
 */
class RepoRootResolver {

    static Path resolve(Path fromDir) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .directory(fromDir.toFile())
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
        return fromDir;
    }
}
