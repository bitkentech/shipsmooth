package io.bitken.ss.resources;

public record Target(Platform platform, Os os, Env env) {

    public static Target from(String platformProp, String envProp) {
        Env env = Env.from(envProp);
        Platform platform = Platform.from(platformProp);
        Os os = "windows".equals(platformProp) ? Os.WINDOWS : Os.POSIX;
        return new Target(platform, os, env);
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
