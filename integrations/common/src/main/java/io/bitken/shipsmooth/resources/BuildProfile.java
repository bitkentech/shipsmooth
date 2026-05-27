package io.bitken.shipsmooth.resources;

public record BuildProfile(String platform, String env, String basePluginName) {

    public boolean isDev()     { return "dev".equals(env); }
    public boolean isGemini()  { return "gemini".equals(platform); }
    public boolean isWindows() { return "windows".equals(platform); }

    public String pluginName()             { return isDev() ? basePluginName + "-dev" : basePluginName; }
    public String skillName(String base)   { return isDev() ? base + "-dev" : base; }
    public String cacheSubdir()            { return isDev() ? basePluginName + "-dev" : basePluginName; }
    public String cliBin(String version)   {
        if (isWindows()) {
            return "%LOCALAPPDATA%\\" + basePluginName + "\\" + version + "\\runtime\\bin\\shipsmooth-tasks.bat";
        }
        return "${XDG_CACHE_HOME:-~/.cache}/" + cacheSubdir() + "/runtime-" + version + "/bin/shipsmooth-tasks";
    }

    public static BuildProfile fromProperties() {
        return new BuildProfile(
            System.getProperty("build.platform", "claude"),
            System.getProperty("build.env", "prod"),
            System.getProperty("plugin.base.name")
        );
    }
}