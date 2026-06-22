package io.bitken.ss.cli.conf.ds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Optional;

/** Derives {@code git remote get-url origin} for the given repo root. */
public final class RemoteUrl {

    private final Path repoRoot;

    public RemoteUrl(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public Optional<String> get() {
        try {
            Process p = new ProcessBuilder("git", "remote", "get-url", "origin")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                int exit = p.waitFor();
                if (exit == 0 && line != null && !line.isBlank()) {
                    return Optional.of(line.trim());
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
