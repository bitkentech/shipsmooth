package io.bitken.ss.resources;

/**
 * Pure platform facts: launcher/java executable names and the CLI-bin path shape
 * for each OS. The hook-command string and its companion-file emission
 * (install-shipsmooth.sh / install-runtime.bat) are NOT here — they are a
 * rendering concern owned by HookCommandRenderer in :plugin-resources, which
 * branches on the Posix/Windows discriminants below (plan-79). Keeping Os free of
 * classpath I/O lets it be a leaf type that :packaging can depend on for nothing
 * more than the OS facts it needs.
 */
public sealed interface Os permits Os.Posix, Os.Windows {

    Os POSIX   = new Posix();
    Os WINDOWS = new Windows();

    String launcherFileName();
    String javaExe();
    String cliBinPath(String pluginName, String version, String cacheSubdir);

    /** Resolves Os from the build.os property ("windows" → WINDOWS, anything else → POSIX). */
    static Os from(String prop) {
        return "windows".equals(prop) ? WINDOWS : POSIX;
    }

    /** Resolves Os from a packaging target string (e.g. "win32-x64" → WINDOWS, "linux-x64" → POSIX). */
    static Os fromPackagingTarget(String target) {
        return target.startsWith("win32") ? WINDOWS : POSIX;
    }

    record Posix() implements Os {

        @Override
        public String launcherFileName() {
            return "shipsmooth";
        }

        @Override
        public String javaExe() {
            return "java";
        }

        @Override
        public String cliBinPath(String pluginName, String version, String cacheSubdir) {
            return "${XDG_CACHE_HOME:-~/.cache}/" + cacheSubdir + "/" + version + "/bin/" + launcherFileName();
        }
    }

    record Windows() implements Os {

        @Override
        public String launcherFileName() {
            return "shipsmooth.cmd";
        }

        @Override
        public String javaExe() {
            return "java.exe";
        }

        @Override
        public String cliBinPath(String pluginName, String version, String cacheSubdir) {
            return "%LOCALAPPDATA%\\" + pluginName + "\\" + version + "\\runtime\\bin\\" + launcherFileName();
        }
    }
}
