package io.bitken.ss.conf;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * Whether experimental mode is active for the current CLI invocation.
 *
 * <p>Single source of truth for the {@code --enable-experimental} gate: parsed
 * once in {@code main()} and injected through Dagger so any feature needing the
 * gate reads it from a final field rather than re-parsing.
 */
public record ExperimentalMode(boolean enabled) {

    private static final String FLAG = "--enable-experimental";

    public static String flag() { return FLAG; }

    /**
     * Probe {@code args} for {@code --enable-experimental} using a minimal
     * picocli spec declaring only that flag, with unmatched args allowed so real
     * args and subcommands are ignored. Runs in {@code main()} before the Dagger
     * graph is built, so it cannot reuse the full command spec (which wraps
     * DI-provided commands).
     */
    public static ExperimentalMode fromArgs(String[] args) {
        CommandSpec spec = CommandSpec.create();
        spec.addOption(OptionSpec.builder(FLAG).type(boolean.class).build());
        CommandLine probe = new CommandLine(spec);
        probe.setUnmatchedArgumentsAllowed(true);
        probe.setUnmatchedOptionsArePositionalParams(true);
        try {
            ParseResult result = probe.parseArgs(args);
            return new ExperimentalMode(result.matchedOptionValue(FLAG, false));
        } catch (Exception e) {
            return new ExperimentalMode(false);
        }
    }
}
