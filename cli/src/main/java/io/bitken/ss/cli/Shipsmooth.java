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
import io.bitken.ss.conf.StateRootUnsettledException;
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
        // The store-init writer needs the single resolution main already computed. Bind it
        // before dispatch (a cheap parse that does not touch state); ignore parse failures
        // here — cmd.execute below re-parses and renders any real usage error itself.
        try {
            bindStoreInit(cmd.parseArgs(args));
        } catch (CommandLine.ParameterException ignored) {
            // genuine bad args — let cmd.execute() report it
        }

        // The tree is comprehensive: --help/--version and state-independent commands run
        // unconditionally. A state-dependent command only touches the state root when its
        // call() resolves the locator (Provider.get()); on an unsettled project that throws
        // StateRootUnsettledException, which this handler turns into the resolution JSON.
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            if (ex instanceof StateRootUnsettledException) {
                return emitGate();
            }
            throw ex;
        });

        return cmd.execute(args);
    }

    /** If the parsed target is {@code store init}, hand it the single resolution. */
    private void bindStoreInit(ParseResult parsed) {
        if (targetUserObject(parsed) instanceof Init init) {
            init.bind(repoRoot, remoteUrl, resolution);
        }
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
            // A settled project never throws StateRootUnsettledException, so the handler that
            // calls this is unreachable when settled; satisfy the switch with a no-op exit.
            case DataStoreResolution.Settled ignored -> 0;
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
