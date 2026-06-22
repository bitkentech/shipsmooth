package io.bitken.ss.cli.conf;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Outcome of resolving where a project's shipsmooth state lives.
 *
 * <p>This is the state space a (future) branch-table {@code resolve()} returns. It has
 * exactly three shapes:
 * <ul>
 *   <li>{@link Settled} — the location is known; proceed with the given store.</li>
 *   <li>{@link NeedsDecision} — the CLI cannot decide alone and must hand the user a set
 *       of options (one marked recommended). The CLI never prompts on stdin; the skill
 *       presents the options and re-invokes the CLI to act on the answer.</li>
 *   <li>{@link Unresolvable} — the CLI cannot proceed and the user must fix it by hand
 *       (ambiguous/corrupt state, a legacy {@code .agents/} tree, a malformed config
 *       entry, or an unexpected checked failure). {@code resolve()} <em>returns</em> this
 *       rather than throwing for these cases.</li>
 * </ul>
 *
 * <p>Note: only the types are introduced here. Wiring {@code resolve()} to produce them,
 * and the pre/post-resolution policy (startup gating, acting on the user's answer), are
 * deliberately out of scope for now.
 */
public sealed interface Resolution
        permits Resolution.Settled, Resolution.NeedsDecision, Resolution.Unresolvable {

    /** Steady state: the location is known — proceed with this store, no skill round-trip. */
    record Settled(ProjectDataStore store) implements Resolution {
    }

    /**
     * Unsettled: the user must choose. Carries why a decision is needed and the concrete
     * options to offer; exactly one option is marked {@code recommended}.
     */
    record NeedsDecision(UndecidableSituation situation, List<Option> options) implements Resolution {

        /** The single option to present as the default/recommended choice. */
        public Option recommended() {
            return options.stream()
                    .filter(Option::recommended)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "NeedsDecision must mark exactly one option recommended"));
        }
    }

    /**
     * The CLI cannot proceed; the user must fix the situation by hand. The human-facing text
     * comes from {@link UnresolvableReason#message()}. {@code cause} carries the underlying
     * throwable for diagnostics when present (e.g. an unexpected checked failure); the
     * anticipated reasons carry no cause.
     */
    record Unresolvable(UnresolvableReason reason, Optional<Throwable> cause)
            implements Resolution {

        /** An anticipated failure described entirely by its reason; no underlying throwable. */
        public static Unresolvable of(UnresolvableReason reason) {
            return new Unresolvable(reason, Optional.empty());
        }

        /** An unexpected (checked) failure, with its cause retained for diagnostics. */
        public static Unresolvable unknown(Throwable cause) {
            return new Unresolvable(UnresolvableReason.UNKNOWN, Optional.of(cause));
        }

        /** The human-readable description of why state is unresolvable. */
        public String message() {
            return reason.message();
        }
    }

    /** Why a decision is needed — lets a later policy/skill layer word the prompt. */
    enum UndecidableSituation {
        /** Nothing configured and no state anywhere: offer external (recommended) or in-repo. */
        CLEAN_FIRST_RUN("No shipsmooth state is configured and none exists yet; choose where it should live."),
        /** A config entry names an external state dir that no longer exists: offer to recreate. */
        CONFIG_DIR_MISSING("The configured external state directory no longer exists; choose whether to recreate it.");

        private final String message;

        UndecidableSituation(String message) {
            this.message = message;
        }

        /** A generic, human-readable description of this situation. */
        public String message() {
            return message;
        }
    }

    /** One choice the user can take, with the path the CLI proposes for it. */
    record Option(Choice choice, Path proposedPath, boolean recommended) {
    }

    /** The kinds of choice offered in a {@link NeedsDecision}. */
    enum Choice {
        EXTERNAL,
        IN_REPO,
        RECREATE_MISSING_DIR
    }

    /**
     * Why state is unresolvable. {@link #UNKNOWN} is the catch-all home for anticipated but
     * unenumerated <em>checked</em> failures; keeping the enum closed lets {@code switch}
     * statements over it stay exhaustive.
     */
    enum UnresolvableReason {
        LEGACY_AGENTS_TREE("A legacy .agents/shipsmooth data tree was found; rename it to .shipsmooth/ by hand."),
        MALFORMED_CONFIG_ENTRY("A matching config entry is malformed (no state directory and no valid mode)."),
        AMBIGUOUS_STATE("The on-disk and configured state are contradictory or corrupt and cannot be reconciled automatically."),
        UNKNOWN("An unexpected error occurred while determining where state lives.");

        private final String message;

        UnresolvableReason(String message) {
            this.message = message;
        }

        /** A generic, human-readable description of this reason. */
        public String message() {
            return message;
        }
    }
}
