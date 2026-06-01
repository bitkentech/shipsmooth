package io.bitken.ss.resources;

public enum Os {
    POSIX, WINDOWS;

    public String launcherFileName() {
        return this == WINDOWS ? "shipsmooth.cmd" : "shipsmooth";
    }

    public String javaExe() {
        return this == WINDOWS ? "java.exe" : "java";
    }

    public String cliBinPath(String pluginName, String version, String cacheSubdir) {
        if (this == WINDOWS) {
            return "%LOCALAPPDATA%\\" + pluginName + "\\" + version + "\\runtime\\bin\\" + launcherFileName();
        }
        return "${XDG_CACHE_HOME:-~/.cache}/" + cacheSubdir + "/runtime-" + version + "/bin/" + launcherFileName();
    }
}
