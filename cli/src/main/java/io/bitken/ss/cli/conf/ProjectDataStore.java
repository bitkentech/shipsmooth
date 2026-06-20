package io.bitken.ss.cli.conf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where this project's shipsmooth state lives.
 *
 * <p>{@link InRepo} is the default (state under the project repo); {@link Standalone}
 * keeps it in a separate directory. Resolved by {@link ProjectDataStoreResolver}.
 *
 * <p>{@link #init()} performs any one-time setup the chosen store needs (creating and
 * git-initialising a standalone state repo, guarding against mixed state); afterwards
 * {@link #stateRoot()} reports the directory under which all shipsmooth state lives.
 */
public interface ProjectDataStore {

    /**
     * Perform one-time setup for this store. No-op for {@link InRepo}.
     *
     * @throws StandaloneConfigException if standalone mode collides with existing
     *         in-repo state (mid-project switching is not supported)
     * @throws IOException if creating or git-initialising the state repo fails
     */
    void init() throws IOException;

    /** The directory under which all shipsmooth state lives. */
    Path stateRoot();

    /** State lives in the project repo (default). */
    final class InRepo implements ProjectDataStore {
        private final Path repoRoot;

        public InRepo(Path repoRoot) {
            this.repoRoot = repoRoot;
        }

        @Override public void init() { /* nothing to set up */ }

        @Override public Path stateRoot() {
            return repoRoot;
        }
    }

    /** State lives in a separate directory, leaving the project repo untouched. */
    final class Standalone implements ProjectDataStore {
        private final Path repoRoot;
        private final Path stateDir;

        public Standalone(Path repoRoot, Path stateDir) {
            this.repoRoot = repoRoot;
            this.stateDir = stateDir;
        }

        @Override public Path stateRoot() {
            return stateDir;
        }

        @Override public void init() throws IOException {
            guardAgainstExistingInRepoState();
            initStateRepoIfAbsent();
        }

        private void guardAgainstExistingInRepoState() {
            // TODO: .agents is hardcoded here. Also what if .agents
            // folder has been created independently of shipsmooth?
            if (Files.exists(repoRoot.resolve(".agents"))) {
                throw new StandaloneConfigException("""
                        standalone mode is configured but .agents/ exists in the project repo.
                        Mid-project switching is not supported. Either:
                          - remove .agents/ from the project repo, or
                          - remove the entry from ~/.config/shipsmooth/ss-config.toml to continue in in-repo mode.""");
            }
        }

        private void initStateRepoIfAbsent() throws IOException {
            // Fast path: already a git repo, nothing to do and no subprocess needed.
            if (Files.isDirectory(stateDir.resolve(".git"))) {
                return;
            }
            // Directory may exist without being a repo (mkdir'd, or an interrupted
            // earlier init); create it only if absent, then git-init either way.
            if (!Files.isDirectory(stateDir)) {
                Files.createDirectories(stateDir);
            }
            try {
                int exit = new ProcessBuilder("git", "init", stateDir.toString())
                        .redirectErrorStream(true)
                        .start()
                        .waitFor();
                if (exit != 0) {
                    throw new IOException("git init failed for " + stateDir);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while initialising state repo", e);
            }
        }
    }
}