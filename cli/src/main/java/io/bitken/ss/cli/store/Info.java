package io.bitken.ss.cli.store;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.cli.ResolutionJson;
import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code store info}: report where this project's shipsmooth state lives, on demand.
 *
 * <p>External-by-default moves plan narratives out of the project repo, so an agent must be
 * told where to read them. This command resolves the project and reports the state location
 * — chiefly the {@code plansDir} the skill points an agent at. With {@code --json} it emits a
 * single machine-readable line; without it, human-readable text. All informational output
 * goes to stdout.
 *
 * <p>Runs whether or not state is settled: a settled project reports {@code ready}; an
 * unsettled one reports the same needs-decision/unresolvable shape as startup.
 */
public class Info implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final ProjectDataStoreResolver resolver;

    private Path repoRoot;
    private Optional<String> remoteUrl = Optional.empty();

    public Info(ProjectDataStoreResolver resolver) {
        this.resolver = resolver;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("info");
        spec.usageMessage().description("Report where this project's shipsmooth state lives.");
        spec.addOption(OptionSpec.builder("--json", "-j").type(boolean.class)
                .description("Emit a single machine-readable JSON line instead of text.").build());
    }

    /** Inject project context (set by {@code main}; defaults to CWD for direct/test use). */
    public void bind(Path repoRoot, Optional<String> remoteUrl) {
        this.repoRoot = repoRoot;
        this.remoteUrl = remoteUrl;
    }

    @Override
    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        Path repo = repoRoot != null ? repoRoot : Path.of(".").toAbsolutePath().normalize();
        boolean json = spec.commandLine().getParseResult().hasMatchedOption("--json");

        DataStoreResolution resolution = resolver.resolve(repo, remoteUrl);
        return switch (resolution) {
            case DataStoreResolution.Settled settled -> {
                StateReport.printReady(repo, settled.store(), json);
                yield 0;
            }
            case DataStoreResolution.NeedsDecision needs -> {
                System.out.println(json
                        ? ResolutionJson.needsDecision(needs)
                        : "shipsmooth state is not set up yet — run `store init`");
                yield 0;
            }
            case DataStoreResolution.Unresolvable bad -> {
                System.out.println(json
                        ? ResolutionJson.unresolvable(bad)
                        : "shipsmooth state is unresolvable: " + bad.message());
                yield 0;
            }
        };
    }
}
