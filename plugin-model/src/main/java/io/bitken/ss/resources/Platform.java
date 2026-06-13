package io.bitken.ss.resources;

public sealed interface Platform permits Platform.Claude, Platform.Gemini, Platform.Codex {

    Platform CLAUDE = new Claude();
    Platform GEMINI = new Gemini();
    Platform CODEX  = new Codex();

    static Platform from(String prop) {
        return switch (prop) {
            case "claude"  -> CLAUDE;
            case "gemini"  -> GEMINI;
            case "codex"   -> CODEX;
            default -> throw new IllegalArgumentException("Unknown platform: " + prop);
        };
    }

    String id();
    String skillFragmentDir();

    default String cacheSubdir(String basePluginName, Env env) {
        return env.decorate(basePluginName);
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
}
