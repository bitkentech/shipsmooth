package io.bitken.ss.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Produces the SessionStart hook command string and emits its OS-specific
 * companion file next to hooks.json — the Posix sh installer
 * ({@code install-shipsmooth.sh}, copied from this module's classpath) or the
 * Windows {@code install-runtime.bat} (generated inline).
 *
 * This logic used to live on {@code Os} (in :plugin-model), but it is a rendering
 * concern that performs classpath/file I/O — so it moved here with the script
 * resource it depends on, keeping {@code Os} a pure leaf type (plan-79 v5). It
 * branches on the {@code Os.Posix}/{@code Os.Windows} discriminants.
 */
class HookCommandRenderer {

    /** Filename of the Node-free POSIX bootstrap script bundled as a classpath resource. */
    static final String INSTALL_SCRIPT_NAME = "install-shipsmooth.sh";

    /**
     * Returns the hook command for {@code os}, writing any companion file into
     * {@code hooksDir} as a side effect (matching the previous Os.hookCommand contract,
     * so the rendered output stays byte-identical).
     */
    String render(Os os, Path hooksDir, String repoName, String pluginName, String version) throws IOException {
        if (os instanceof Os.Windows) {
            return windowsCommand(hooksDir, repoName, pluginName, version);
        }
        return posixCommand(hooksDir);
    }

    private String posixCommand(Path hooksDir) throws IOException {
        String command = System.getProperty("plugin.hook.command",
            "node \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"");
        // When the hook bootstraps via the Node-free sh installer (prod variants),
        // copy the static script next to hooks.json — the Posix sibling of the
        // Windows install-runtime.bat. The hook passes name+version as args, so the
        // script itself is a plain, lintable file with no baked values.
        if (command.contains(INSTALL_SCRIPT_NAME)) {
            copyResource(INSTALL_SCRIPT_NAME, hooksDir.resolve(INSTALL_SCRIPT_NAME));
        }
        return command;
    }

    private String windowsCommand(Path hooksDir, String repoName, String pluginName, String version) throws IOException {
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

    /**
     * Copies a bundled classpath resource to {@code dest}, creating parent dirs.
     * Package-private (not private) so a test can drive the missing-resource branch
     * with a bogus name without reflection.
     */
    void copyResource(String resourceName, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        try (var in = HookCommandRenderer.class.getResourceAsStream("/" + resourceName)) {
            if (in == null) {
                throw new IOException("bundled " + resourceName + " not found on classpath");
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
