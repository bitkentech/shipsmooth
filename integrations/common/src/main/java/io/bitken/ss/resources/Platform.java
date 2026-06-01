package io.bitken.ss.resources;

public enum Platform {
    CLAUDE, GEMINI;

    public String id() {
        return name().toLowerCase();
    }

    public String skillFragmentDir() {
        return "start/" + id();
    }

    public String cacheSubdir(String basePluginName, Env env) {
        return env.decorate(basePluginName);
    }
}
