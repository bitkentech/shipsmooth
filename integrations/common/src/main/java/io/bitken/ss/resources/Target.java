package io.bitken.ss.resources;

public record Target(Platform platform, Os os, Env env) {

    public static Target from(String platformProp, String envProp) {
        Env env = "dev".equals(envProp) ? Env.DEV : Env.PROD;
        return switch (platformProp) {
            case "claude"   -> new Target(Platform.CLAUDE, Os.POSIX,    env);
            case "windows"  -> new Target(Platform.CLAUDE, Os.WINDOWS,  env);
            case "gemini"   -> new Target(Platform.GEMINI, Os.POSIX,    env);
            default -> throw new IllegalArgumentException("Unknown platform: " + platformProp);
        };
    }

    public String cliBin(String pluginName, String version) {
        return os.cliBinPath(pluginName, version, platform.cacheSubdir(pluginName, env));
    }

    public String skillFragmentDir() {
        return platform.skillFragmentDir();
    }

    public String launcherFileName() {
        return os.launcherFileName();
    }
}
