package io.bitken.ss.resources;

public record Target(Platform platform, Os os, Env env) {

    public static Target from(String platformProp, String osProp, String envProp) {
        return new Target(Platform.from(platformProp), Os.from(osProp), Env.from(envProp));
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
