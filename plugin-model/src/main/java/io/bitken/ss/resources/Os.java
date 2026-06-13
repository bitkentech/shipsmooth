package io.bitken.ss.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public sealed interface Os permits Os.Posix, Os.Windows {

    Os POSIX   = new Posix();
    Os WINDOWS = new Windows();

    /** Filename of the Node-free POSIX bootstrap script bundled as a classpath resource. */
    String INSTALL_SCRIPT_NAME = "install-shipsmooth.sh";

    String launcherFileName();
    String javaExe();
    String cliBinPath(String pluginName, String version, String cacheSubdir);
    String hookCommand(Path hooksDir, String repoName, String pluginName, String version) throws IOException;

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

        @Override
        public String hookCommand(Path hooksDir, String repoName, String pluginName, String version) throws IOException {
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

        /**
         * Copies a bundled classpath resource to {@code dest}, creating parent dirs.
         * Package-private (not private) so a test can drive the missing-resource branch
         * with a bogus name without reflection.
         */
        void copyResource(String resourceName, Path dest) throws IOException {
            Files.createDirectories(dest.getParent());
            try (var in = Os.class.getResourceAsStream("/" + resourceName)) {
                if (in == null) {
                    throw new IOException("bundled " + resourceName + " not found on classpath");
                }
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
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

        @Override
        public String hookCommand(Path hooksDir, String repoName, String pluginName, String version) throws IOException {
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
}
