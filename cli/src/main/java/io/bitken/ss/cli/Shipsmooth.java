package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.cli.conf.RemoteUrl;
import io.bitken.ss.cli.conf.ResolvedMode;
import io.bitken.ss.cli.conf.StandaloneConfigException;
import io.bitken.ss.cli.conf.StandaloneConfigResolver;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * One-shot shipsmooth CLI: bound to a fixed argv at construction and run once
 * via {@link #execute()}.
 */
public class Shipsmooth {

    private final CommandTree commandTree;
    private final CommandLine cmd;
    private final String[] args;

    public Shipsmooth(AppComponents app, String[] args) {
        this.args = args;
        this.commandTree = new CommandTree(app);
        this.cmd = commandTree.commandLine();
    }

    public int execute() {
        return cmd.execute(args);
    }

    /** Test seam for the integration command. */
    public Integrate integrateCommand() {
        return commandTree.integrate();
    }

    public static void main(String[] args) {
        Path repoRoot = new RepoRoot(Paths.get(".")).path();
        Optional<String> remoteUrl = new RemoteUrl(repoRoot).get();

        Path stateRoot;
        try {
            ResolvedMode mode = new StandaloneConfigResolver().resolve(repoRoot, remoteUrl);
            stateRoot = switch (mode) {
                case ResolvedMode.InRepo() -> repoRoot;
                case ResolvedMode.Standalone(Path stateDir) -> {
                    guardAgainstExistingInRepoState(repoRoot);
                    initStateRepoIfAbsent(stateDir);
                    yield stateDir;
                }
            };
        } catch (StandaloneConfigException e) {
            System.err.println("shipsmooth: config error: " + e.getMessage());
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("shipsmooth: failed to initialise standalone state repo: " + e.getMessage());
            System.exit(1);
            return;
        }

        AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(
                repoRoot,
                stateRoot,
                ExperimentalModeParser.fromArgs(args)))
            .build();

        int exitCode = new Shipsmooth(app, args).execute();
        System.exit(exitCode);
    }

    private static void guardAgainstExistingInRepoState(Path repoRoot) {
        if (Files.exists(repoRoot.resolve(".agents"))) {
            System.err.println("""
                    shipsmooth: standalone mode is configured but .agents/ exists in the project repo.
                    Mid-project switching is not supported. Either:
                      - remove .agents/ from the project repo, or
                      - remove the entry from ~/.config/shipsmooth/ss-config.toml to continue in in-repo mode.""");
            System.exit(1);
        }
    }

    private static void initStateRepoIfAbsent(Path stateDir) throws IOException {
        if (Files.exists(stateDir)) {
            return;
        }
        Files.createDirectories(stateDir);
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
