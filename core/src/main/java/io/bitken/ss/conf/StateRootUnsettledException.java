package io.bitken.ss.conf;

/**
 * Thrown when a command demands the state root (by resolving the data locator) but the
 * project's shipsmooth state is not settled yet — a clean first run, or a configured state
 * dir that does not exist.
 *
 * <p>This is the gate seam: the command tree is built comprehensively even on an unsettled
 * project (so {@code --help} lists everything and parses), and the state root is only
 * touched when a state-dependent command actually runs ({@code Provider.get()} inside its
 * {@code call()}). At that point this is thrown. The cli layer — which owns the rich
 * {@code DataStoreResolution} — catches it and emits the needs-decision / unresolvable
 * result for the skill, on a distinct exit code. Core deliberately knows only "unsettled",
 * not why; the messaging is the cli's job.
 */
public class StateRootUnsettledException extends RuntimeException {
    public StateRootUnsettledException(String message) {
        super(message);
    }
}
