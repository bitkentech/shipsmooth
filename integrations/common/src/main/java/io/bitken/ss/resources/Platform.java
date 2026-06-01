package io.bitken.ss.resources;

public sealed interface Platform permits Platform.Claude, Platform.Gemini {

    Platform CLAUDE = new Claude();
    Platform GEMINI = new Gemini();

    String id();
    String skillFragmentDir();
    String cacheSubdir(String basePluginName, Env env);

    record Claude() implements Platform {

        @Override
        public String id() {
            return "claude";
        }

        @Override
        public String skillFragmentDir() {
            return "start/claude";
        }

        @Override
        public String cacheSubdir(String basePluginName, Env env) {
            return env.decorate(basePluginName);
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

        @Override
        public String cacheSubdir(String basePluginName, Env env) {
            return env.decorate(basePluginName);
        }
    }
}
