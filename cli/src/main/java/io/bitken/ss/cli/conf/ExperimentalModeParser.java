package io.bitken.ss.cli.conf;

import io.bitken.ss.conf.ExperimentalMode;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * Resolves {@link ExperimentalMode} from raw CLI args using a minimal picocli
 * spec declaring only {@code --enable-experimental}, with unmatched args allowed
 * so real args and subcommands are ignored. Runs in {@code main()} before the
 * Dagger graph is built, so it cannot reuse the full command spec (which wraps
 * DI-provided commands).
 *
 * <p>Lives in the CLI module because parsing argv is a target concern; core only
 * holds the resulting {@link ExperimentalMode} value.
 */
public final class ExperimentalModeParser {

    private ExperimentalModeParser() {}

    public static ExperimentalMode fromArgs(String[] args) {
        CommandSpec spec = CommandSpec.create();
        spec.addOption(OptionSpec.builder(ExperimentalMode.FLAG).type(boolean.class).build());
        CommandLine probe = new CommandLine(spec);
        probe.setUnmatchedArgumentsAllowed(true);
        probe.setUnmatchedOptionsArePositionalParams(true);
        try {
            ParseResult result = probe.parseArgs(args);
            return new ExperimentalMode(result.matchedOptionValue(ExperimentalMode.FLAG, false));
        } catch (Exception e) {
            return new ExperimentalMode(false);
        }
    }
}
