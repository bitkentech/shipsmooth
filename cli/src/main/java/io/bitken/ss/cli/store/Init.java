package io.bitken.ss.cli.store;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.cli.conf.ds.ConfigWriter;
import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import io.bitken.ss.cli.conf.ds.ProjectDataStore;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code store init}: acts on the user's first-run choice — creates the chosen state
 * directory and records it in {@code shipsmooth.toml}, turning a {@code NeedsDecision}
 * resolution into a {@code Settled} one.
 *
 * <p>A <em>guarded</em> writer. It does not decide for itself whether to act: {@code main}
 * resolves once and injects that {@link DataStoreResolution} via {@link #bind}. This command
 * refuses to mutate unless the project is genuinely awaiting a decision and the supplied
 * {@code --type} is one the current situation offers — an already-settled or unresolvable
 * project, or an off-menu choice, is rejected without touching anything. After acting it
 * re-resolves once to confirm the project actually settled.
 */
public class Init implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final ProjectDataStoreResolver resolver;
    private final ConfigWriter configWriter;

    private Path repoRoot;
    private Optional<String> remoteUrl = Optional.empty();
    private DataStoreResolution resolution;

    public Init(ProjectDataStoreResolver resolver, ConfigWriter configWriter) {
        this.resolver = resolver;
        this.configWriter = configWriter;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("init");
        spec.usageMessage().description(
                "Act on a first-run choice: create the chosen state location and record it.");
        spec.addOption(OptionSpec.builder("--type").required(true).type(String.class)
                .paramLabel("TYPE").description("embedded | filesystem | recreate").build());
        spec.addOption(OptionSpec.builder("--path").type(String.class)
                .paramLabel("PATH").description("State directory (for external/recreate)").build());
        spec.addOption(OptionSpec.builder("--json", "-j").type(boolean.class)
                .description("Emit the resulting state location as a machine-readable JSON line.").build());
    }

    /**
     * Inject the project context and the single resolution {@code main} already computed,
     * so this command validates against it rather than resolving a second time.
     */
    public void bind(Path repoRoot, Optional<String> remoteUrl, DataStoreResolution resolution) {
        this.repoRoot = repoRoot;
        this.remoteUrl = remoteUrl;
        this.resolution = resolution;
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        if (resolution == null) {
            return fail("internal error: store init was not bound to a resolution");
        }

        var pr = spec.commandLine().getParseResult();
        String typeArg = pr.matchedOption("type").getValue();
        Optional<String> pathArg = Optional.ofNullable(pr.matchedOptionValue("path", null));

        DataStoreResolution.Choice choice = parseType(typeArg);
        if (choice == null) {
            return fail("unknown --type '" + typeArg + "' (expected embedded | filesystem | recreate)");
        }

        // Sealed switch (no default): adding a DataStoreResolution subtype breaks this at
        // compile time rather than at the old unchecked cast. Terminal cases return; only
        // NeedsDecision continues, so `needs` is definitely assigned below.
        final DataStoreResolution.NeedsDecision needs;
        switch (resolution) {
            case DataStoreResolution.Settled ignored -> {
                return fail("this project is already configured; nothing to do");
            }
            case DataStoreResolution.Unresolvable bad -> {
                return fail(bad.message());
            }
            case DataStoreResolution.NeedsDecision n -> needs = n;
        }

        DataStoreResolution.Option option = needs.options().stream()
                .filter(o -> o.choice() == choice)
                .findFirst()
                .orElse(null);
        if (option == null) {
            return fail("--type " + typeArg + " is not valid for the current situation ("
                    + needs.situation() + ")");
        }

        act(choice, option, pathArg);

        // Confirm the action settled the project.
        DataStoreResolution after = resolver.resolve(repoRoot, remoteUrl);
        if (!(after instanceof DataStoreResolution.Settled settled)) {
            return fail("state did not settle after acting on the choice");
        }
        boolean json = pr.hasMatchedOption("--json");
        StateReport.printReady(repoRoot, settled.store(), json);
        return 0;
    }

    private void act(DataStoreResolution.Choice choice, DataStoreResolution.Option option,
                     Optional<String> pathArg) throws IOException {
        switch (choice) {
            case EXTERNAL -> {
                Path dir = resolvePath(pathArg, option.proposedPath());
                new ProjectDataStore.Standalone(repoRoot, dir).init();
                configWriter.writeExternal(repoRoot, remoteUrl, dir);
            }
            case RECREATE_MISSING_DIR -> {
                Path dir = resolvePath(pathArg, option.proposedPath());
                // Already configured — provision the dir, do not touch the config.
                new ProjectDataStore.Standalone(repoRoot, dir).init();
            }
            case IN_REPO -> {
                // Provision the in-repo data folder so the project resolves settled next run;
                // record the in-repo choice in config so it is not re-asked.
                Files.createDirectories(repoRoot.resolve(".shipsmooth").resolve("plans"));
                configWriter.writeInRepo(repoRoot, remoteUrl);
            }
        }
    }

    /** Use the user-supplied path if given, else the path the resolver proposed. */
    private static Path resolvePath(Optional<String> pathArg, Path proposed) {
        return pathArg.map(p -> Path.of(p).toAbsolutePath().normalize()).orElse(proposed);
    }

    private static DataStoreResolution.Choice parseType(String arg) {
        return switch (arg == null ? "" : arg.trim()) {
            case "filesystem" -> DataStoreResolution.Choice.EXTERNAL;
            case "embedded" -> DataStoreResolution.Choice.IN_REPO;
            case "recreate" -> DataStoreResolution.Choice.RECREATE_MISSING_DIR;
            default -> null;
        };
    }

    private static int fail(String message) {
        System.err.println("shipsmooth: " + message);
        return 1;
    }
}
