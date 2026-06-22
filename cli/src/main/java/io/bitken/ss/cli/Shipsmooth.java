package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import io.bitken.ss.cli.conf.ds.RemoteUrl;
import io.bitken.ss.cli.conf.ds.ProjectDataStore;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import io.bitken.ss.cli.conf.ds.StandaloneConfigException;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import picocli.CommandLine;

import java.io.IOException;
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

    public static void main(String[] args) {
        Path repoRoot = new RepoRoot(Paths.get(".")).path();
        Optional<String> remoteUrl = new RemoteUrl(repoRoot).get();

        DataStoreResolution resolution = new ProjectDataStoreResolver().resolve(repoRoot, remoteUrl);

        // TODO(plan-85 Task 5): handle NeedsDecision via the skill handshake (present options,
        // act on the answer). For now only the settled steady state proceeds; the unsettled
        // and unresolvable cases fail loudly rather than guessing a location.
        ProjectDataStore dataStore;
        switch (resolution) {
            case DataStoreResolution.Settled settled -> dataStore = settled.store();
            case DataStoreResolution.NeedsDecision needs -> {
                System.err.println("shipsmooth: " + needs.situation().message()
                        + " (interactive setup is not wired yet)");
                System.exit(1);
                return;
            }
            case DataStoreResolution.Unresolvable bad -> {
                System.err.println("shipsmooth: " + bad.message());
                System.exit(1);
                return;
            }
        }

        Path stateRoot;
        try {
            dataStore.init();
            stateRoot = dataStore.stateRoot();
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
}
