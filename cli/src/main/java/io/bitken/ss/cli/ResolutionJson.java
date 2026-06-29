package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ds.DataStoreResolution;

import java.util.StringJoiner;

/**
 * Serialises a {@link DataStoreResolution} to a single JSON line for the skill to consume.
 *
 * <p>The skill parses one shape per status — {@code needs-decision} or {@code unresolvable}
 * — and never has to read stderr. Hand-built to avoid pulling a JSON dependency into the
 * startup path; the field set is small and fixed.
 */
public final class ResolutionJson {

    private ResolutionJson() {
    }

    public static String needsDecision(DataStoreResolution.NeedsDecision needs) {
        StringJoiner options = new StringJoiner(",", "[", "]");
        for (DataStoreResolution.Option o : needs.options()) {
            options.add("{"
                    + kv("choice", choiceToken(o.choice())) + ","
                    + kv("proposedPath", o.proposedPath().toString()) + ","
                    + "\"recommended\":" + o.recommended()
                    + "}");
        }
        return "{"
                + kv("status", "needs-decision") + ","
                + kv("situation", situationToken(needs.situation())) + ","
                + kv("message", needs.situation().message()) + ","
                + kv("prompt", prompt(needs)) + ","
                + "\"options\":" + options
                + "}";
    }

    /**
     * A display-ready, multi-line rendering of the decision the skill shows the user
     * verbatim: the situation message, then one line per option with its path and the
     * recommended one marked. Keeping the rendering here (the brain) keeps the skill's job
     * to "show this and capture the answer" — it never has to compose the prompt itself.
     */
    private static String prompt(DataStoreResolution.NeedsDecision needs) {
        StringBuilder sb = new StringBuilder(needs.situation().message());
        boolean offersExternal = false;
        for (DataStoreResolution.Option o : needs.options()) {
            sb.append("\n  ").append(o.recommended() ? "Recommended" : "Alternative")
                    .append(" — ").append(optionLabel(o.choice()))
                    .append(": ").append(o.proposedPath());
            offersExternal |= o.choice() == DataStoreResolution.Choice.EXTERNAL;
        }
        // When a separate folder is on offer, the proposed path is only a default.
        if (offersExternal) {
            sb.append("\n\nYou can also enter a different folder path.");
        }
        return sb.toString();
    }

    /** Human-facing label for an option in the prompt (the skill shows this verbatim). */
    private static String optionLabel(DataStoreResolution.Choice c) {
        return switch (c) {
            case EXTERNAL -> "a separate folder next to this repo";
            case IN_REPO -> "inside this repo";
            case RECREATE_MISSING_DIR -> "recreate the configured folder";
        };
    }

    public static String unresolvable(DataStoreResolution.Unresolvable bad) {
        return "{"
                + kv("status", "unresolvable") + ","
                + kv("reason", bad.reason().name()) + ","
                + kv("message", bad.message())
                + "}";
    }

    /**
     * Settled state: where shipsmooth state lives. {@code storageType} is {@code filesystem}
     * or {@code embedded}; {@code plansDir} is the ready-to-read directory holding plan files,
     * so the skill can point an agent straight at plan context.
     */
    public static String ready(String storageType, java.nio.file.Path stateRoot, java.nio.file.Path plansDir) {
        return "{"
                + kv("status", "ready") + ","
                + kv("storageType", storageType) + ","
                + kv("stateRoot", stateRoot.toString()) + ","
                + kv("plansDir", plansDir.toString())
                + "}";
    }

    /**
     * Stable wire tokens for the skill, independent of enum naming. These are the values the
     * skill passes back to {@code store init --type}, so they must match that flag's accepted
     * values exactly: {@code filesystem} / {@code embedded} / {@code recreate}.
     */
    private static String choiceToken(DataStoreResolution.Choice c) {
        return switch (c) {
            case EXTERNAL -> "filesystem";
            case IN_REPO -> "embedded";
            case RECREATE_MISSING_DIR -> "recreate";
        };
    }

    private static String situationToken(DataStoreResolution.UndecidableSituation s) {
        return switch (s) {
            case CLEAN_FIRST_RUN -> "clean-first-run";
            case CONFIG_DIR_MISSING -> "config-dir-missing";
            case IN_REPO_NOT_SET_UP -> "in-repo-not-set-up";
        };
    }

    private static String kv(String key, String value) {
        return "\"" + key + "\":\"" + escape(value) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
