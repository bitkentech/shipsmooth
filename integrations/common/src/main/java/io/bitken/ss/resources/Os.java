package io.bitken.ss.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** Writes any OS-specific hook files to hooksDir and returns the hook command string. */
    public String hookCommand(Path hooksDir, String repoName, String pluginName, String version) throws IOException {
        if (this == POSIX) {
            return System.getProperty("plugin.hook.command", "node \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"");
        }
        String cacheRoot = windowsCacheRoot(repoName, version);
        Files.writeString(hooksDir.resolve("install-runtime.bat"), installRuntimeBatContent(pluginName, version, cacheRoot));
        // MSYS_NO_PATHCONV=1 prevents Git Bash's MSYS2 layer from translating /C to C:
        return "MSYS_NO_PATHCONV=1 cmd.exe /C \"" + cacheRoot + "\\hooks\\install-runtime.bat\"";
    }

    private String windowsCacheRoot(String repoName, String version) {
        return "%USERPROFILE%\\.claude\\plugins\\cache\\bitkentech\\" + repoName + "\\" + version;
    }

    private String installRuntimeBatContent(String pluginName, String version, String cacheRoot) {
        String dest = "%LOCALAPPDATA%\\" + pluginName + "\\" + version + "\\runtime";
        String src  = cacheRoot + "\\runtime";
        return "@echo off\r\n" +
               "if exist \"" + src + "\" (\r\n" +
               "    mkdir \"" + dest + "\" 2>nul\r\n" +
               "    xcopy /E /Y /I \"" + src + "\" \"" + dest + "\"\r\n" +
               ")\r\n";
    }
}
