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
                + "\"options\":" + options
                + "}";
    }

    public static String unresolvable(DataStoreResolution.Unresolvable bad) {
        return "{"
                + kv("status", "unresolvable") + ","
                + kv("reason", bad.reason().name()) + ","
                + kv("message", bad.message())
                + "}";
    }

    /**
     * Settled state: where shipsmooth state lives. {@code mode} is {@code external} or
     * {@code in-repo}; {@code plansDir} is the ready-to-read directory holding plan files,
     * so the skill can point an agent straight at plan context.
     */
    public static String ready(String mode, java.nio.file.Path stateRoot, java.nio.file.Path plansDir) {
        return "{"
                + kv("status", "ready") + ","
                + kv("mode", mode) + ","
                + kv("stateRoot", stateRoot.toString()) + ","
                + kv("plansDir", plansDir.toString())
                + "}";
    }

    /** Stable wire tokens for the skill (kebab-case), independent of enum naming. */
    private static String choiceToken(DataStoreResolution.Choice c) {
        return switch (c) {
            case EXTERNAL -> "external";
            case IN_REPO -> "in-repo";
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
