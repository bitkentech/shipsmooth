package io.bitken.ss.resources;

public sealed interface Platform
    permits Platform.Claude, Platform.Gemini, Platform.Codex, Platform.Opencode {

    Platform CLAUDE   = new Claude();
    Platform GEMINI   = new Gemini();
    Platform CODEX    = new Codex();
    Platform OPENCODE = new Opencode();

    static Platform from(String prop) {
        return switch (prop) {
            case "claude"   -> CLAUDE;
            case "gemini"   -> GEMINI;
            case "codex"    -> CODEX;
            case "opencode" -> OPENCODE;
            default -> throw new IllegalArgumentException("Unknown platform: " + prop);
        };
    }

    String id();
    String skillFragmentDir();

    default String cacheSubdir(String basePluginName, Env env) {
        return env.decorate(basePluginName);
    }

    /**
     * Whether this host consumes a {@code hooks/hooks.json} SessionStart manifest.
     * Hook-based hosts (Claude/Codex/Gemini) emit it; OpenCode does not — it has no
     * SessionStart-hook mechanism and bootstraps from its JS plugin instead, so
     * {@code Target} ships the installer script but skips the JSON (plan-86 Task 2).
     */
    default boolean emitsHooksJson() {
        return true;
    }

    record Claude() implements Platform {

        @Override
        public String id() {
            return "claude";
        }

        @Override
        public String skillFragmentDir() {
            return "start/claude";
        }
    }

    record Gemini() implements Platform {

        @Override
        public String id() {
            return "gemini";
        }

        @Override
        public String skillFragmentDir() {
            return "start/gemini";
        }
    }

    record Codex() implements Platform {

        @Override
        public String id() {
            return "codex";
        }

        @Override
        public String skillFragmentDir() {
            return "start/codex";
        }
    }

    record Opencode() implements Platform {

        @Override
        public String id() {
            return "opencode";
        }

        @Override
        public String skillFragmentDir() {
            return "start/opencode";
        }

        @Override
        public boolean emitsHooksJson() {
            return false;
        }
    }
}
