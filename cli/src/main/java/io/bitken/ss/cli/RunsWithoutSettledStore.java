package io.bitken.ss.cli;

/**
 * Marker for commands that can run before the project's shipsmooth state is settled.
 *
 * <p>By default every command needs a settled store (a known {@code stateRoot}); those
 * commands are gated at startup and, when state is unsettled, the CLI emits a
 * needs-decision/unresolvable result instead of running them. The few commands that
 * operate <em>on</em> the unsettled state itself — chiefly {@code store init}, which
 * creates the state location — opt out by implementing this and returning {@code true}.
 */
public interface RunsWithoutSettledStore {
    default boolean runsWithoutSettledStore() {
        return false;
    }
}
