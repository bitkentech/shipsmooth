package io.bitken.ss.resources;

public sealed interface Platform permits Platform.Claude, Platform.Gemini {

    Platform CLAUDE = new Claude();
    Platform GEMINI = new Gemini();

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
}
