package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import io.bitken.ss.cli.conf.ds.RemoteUrl;
import io.bitken.ss.cli.conf.ds.ProjectDataStore;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import io.bitken.ss.cli.conf.ds.StandaloneConfigException;
import io.bitken.ss.cli.store.Init;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * One-shot shipsmooth CLI: bound to a fixed argv at construction and run once
 * via {@link #execute()}.
 */
public class Shipsmooth {

    /** Exit codes the skill branches on when startup cannot settle the store. */
    static final int EXIT_NEEDS_DECISION = 10;
    static final int EXIT_UNRESOLVABLE = 11;

    private final CommandLine cmd;
    private final String[] args;
    private final Path repoRoot;
    private final Optional<String> remoteUrl;
    private final DataStoreResolution resolution;

    public Shipsmooth(AppComponents app, String[] args,
                      Path repoRoot, Optional<String> remoteUrl, DataStoreResolution resolution) {
        this.args = args;
        this.repoRoot = repoRoot;
        this.remoteUrl = remoteUrl;
        this.resolution = resolution;
        boolean settled = resolution instanceof DataStoreResolution.Settled;
        this.cmd = new CommandTree(app, settled).commandLine();
    }

    /**
     * Convenience for callers (and tests) that already have a settled, in-repo app: assumes
     * the store is settled at the current working directory, so the resolution gate is a
     * no-op and the requested command runs directly.
     */
    public Shipsmooth(AppComponents app, String[] args) {
        this(app, args, Paths.get(".").toAbsolutePath().normalize(), Optional.empty(),
                new DataStoreResolution.Settled(
                        new ProjectDataStore.InRepo(Paths.get(".").toAbsolutePath().normalize())));
    }

    public int execute() {
        boolean settled = resolution instanceof DataStoreResolution.Settled;

        ParseResult parsed;
        try {
            parsed = cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            // Unsettled: the requested command is not in the (no-settle-only) tree, so it
            // needs a settled store — emit the resolution instead of a usage error.
            if (!settled) {
                return emitGate();
            }
            return cmd.execute(args);
        }

        Object target = targetUserObject(parsed);

        // The store-init writer is handed the single resolution so it validates against it.
        if (target instanceof Init init) {
            init.bind(repoRoot, remoteUrl, resolution);
        }

        // Settled commands cannot run while unsettled: emit the resolution as JSON for the
        // skill on a distinct exit code, instead of dispatching.
        if (!settled && !runsWithoutSettledStore(target)) {
            return emitGate();
        }

        return cmd.execute(args);
    }

    /** Print the (unsettled) resolution as JSON and return its exit code. */
    private int emitGate() {
        return switch (resolution) {
            case DataStoreResolution.NeedsDecision needs -> {
                System.out.println(ResolutionJson.needsDecision(needs));
                yield EXIT_NEEDS_DECISION;
            }
            case DataStoreResolution.Unresolvable bad -> {
                System.out.println(ResolutionJson.unresolvable(bad));
                yield EXIT_UNRESOLVABLE;
            }
            case DataStoreResolution.Settled ignored -> cmd.execute(args); // unreachable
        };
    }

    /** The deepest matched command's user object (the command instance), or null. */
    private static Object targetUserObject(ParseResult parsed) {
        ParseResult pr = parsed;
        while (pr.hasSubcommand()) {
            pr = pr.subcommand();
        }
        return pr.commandSpec().userObject();
    }

    private static boolean runsWithoutSettledStore(Object target) {
        return target instanceof RunsWithoutSettledStore r && r.runsWithoutSettledStore();
    }

    public static void main(String[] args) {
        Path repoRoot = new RepoRoot(Paths.get(".")).path();
        Optional<String> remoteUrl = new RemoteUrl(repoRoot).get();
        ExperimentalMode experimentalMode = ExperimentalModeParser.fromArgs(args);

        // Single resolution per invocation — the one source of truth for this run.
        DataStoreResolution resolution = new ProjectDataStoreResolver().resolve(repoRoot, remoteUrl);

        ServicesModule module;
        if (resolution instanceof DataStoreResolution.Settled settled) {
            try {
                ProjectDataStore store = settled.store();
                store.init();
                module = new ServicesModule(repoRoot, store.stateRoot(), experimentalMode);
            } catch (StandaloneConfigException e) {
                System.err.println("shipsmooth: config error: " + e.getMessage());
                System.exit(1);
                return;
            } catch (IOException e) {
                System.err.println("shipsmooth: failed to initialise standalone state repo: " + e.getMessage());
                System.exit(1);
                return;
            }
        } else {
            // Unsettled: build the app without a state root so state-independent commands
            // (e.g. `store init`) can still run; the gate handles everything else.
            module = ServicesModule.unsettled(repoRoot, experimentalMode);
        }

        AppComponents app = DaggerAppComponents.builder().servicesModule(module).build();
        int exitCode = new Shipsmooth(app, args, repoRoot, remoteUrl, resolution).execute();
        System.exit(exitCode);
    }
}
