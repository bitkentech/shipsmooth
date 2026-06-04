package io.bitken.ss.conf;

/**
 * Whether experimental mode is active for the current invocation.
 *
 * <p>Single source of truth for the experimental gate: resolved once at the
 * target boundary (e.g. CLI argument parsing) and injected through Dagger so any
 * feature needing the gate reads it from a final field. Target-agnostic: core
 * holds the value, not the mechanism that produces it. The CLI resolves it via
 * {@code io.bitken.ss.cli.conf.ExperimentalModeParser}.
 */
public record ExperimentalMode(boolean enabled) {

    /** The canonical flag name targets expose to toggle experimental mode. */
    public static final String FLAG = "--enable-experimental";
}
