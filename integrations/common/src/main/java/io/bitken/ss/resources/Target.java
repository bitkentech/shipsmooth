package io.bitken.ss.resources;

public record Target(Platform platform, Os os, Env env) {

    public static Target from(String platformProp, String osProp, String envProp) {
        Platform platform = Platform.from(platformProp);
        Os os = Os.from(osProp);
        Env env = Env.from(envProp);
        if (os == Os.WINDOWS && platform != Platform.CLAUDE) {
            throw new IllegalArgumentException("Windows is only supported with the Claude platform, got: " + platformProp);
        }
        if (os == Os.WINDOWS && env == Env.DEV) {
            throw new IllegalArgumentException("Windows + Dev environment is not supported");
        }
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
