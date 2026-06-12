package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OsTest {

    @Test
    void posix_launcherFileName_isShipsmooth() {
        assertEquals("shipsmooth", Os.POSIX.launcherFileName());
    }

    @Test
    void windows_launcherFileName_isShipsmoothCmd() {
        // Must match what PackageRuntime writes — single source of truth
        assertEquals("shipsmooth.cmd", Os.WINDOWS.launcherFileName());
    }

    @Test
    void posix_javaExe_isJava() {
        assertEquals("java", Os.POSIX.javaExe());
    }

    @Test
    void windows_javaExe_isJavaExe() {
        assertEquals("java.exe", Os.WINDOWS.javaExe());
    }

    @Test
    void posix_cliBinPath_usesXdgCacheHome() {
        String result = Os.POSIX.cliBinPath("shipsmooth", "0.3.3", "shipsmooth");
        assertEquals("${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.3/bin/shipsmooth", result);
    }

    @Test
    void posix_cliBinPath_devSubdirVariant() {
        String result = Os.POSIX.cliBinPath("shipsmooth", "0.3.3", "shipsmooth-dev");
        assertEquals("${XDG_CACHE_HOME:-~/.cache}/shipsmooth-dev/0.3.3/bin/shipsmooth", result);
    }

    @Test
    void windows_cliBinPath_usesLocalAppData() {
        // Must match the old BuildProfile.cliBin() for windows — guards against output drift
        String result = Os.WINDOWS.cliBinPath("shipsmooth", "0.3.10", "shipsmooth");
        assertEquals("%LOCALAPPDATA%\\shipsmooth\\0.3.10\\runtime\\bin\\shipsmooth.cmd", result);
    }

    @Test
    void windows_cliBinPath_launcherExtension_isCmd_notBat() {
        String result = Os.WINDOWS.cliBinPath("shipsmooth", "0.3.10", "shipsmooth");
        assertTrue(result.endsWith("shipsmooth.cmd"), "Windows cliBinPath must end with shipsmooth.cmd, not .bat");
    }

    @Test
    void windows_hookCommand_writesBatAndReturnsCmdExe(@TempDir Path hooksDir) throws Exception {
        String cmd = Os.WINDOWS.hookCommand(hooksDir, "shipsmooth-windows", "shipsmooth", "0.3.10");
        assertTrue(cmd.contains("cmd.exe"), "Windows hook must use cmd.exe");
        assertTrue(cmd.contains("install-runtime.bat"), "Windows hook must reference bat file");
        assertTrue(cmd.contains("MSYS_NO_PATHCONV=1"), "Must set MSYS_NO_PATHCONV");
        assertTrue(cmd.contains("shipsmooth-windows"), "Cache root must use repo name");

        Path bat = hooksDir.resolve("install-runtime.bat");
        assertTrue(Files.exists(bat), "install-runtime.bat must be written");
        String batContent = Files.readString(bat);
        assertTrue(batContent.contains("xcopy"), "bat must use xcopy");
        assertTrue(batContent.contains("LOCALAPPDATA"), "bat destination must use LOCALAPPDATA");
        assertTrue(batContent.contains("\\shipsmooth\\"), "bat destination must use plugin name");
    }

    @Test
    void posix_hookCommand_returnsNodeDefault_andWritesNothing(@TempDir Path hooksDir) throws Exception {
        // No plugin.hook.command set (dev / default) -> Node entry point, no script copied.
        String cmd = Os.POSIX.hookCommand(hooksDir, "shipsmooth", "shipsmooth", "0.3.3");
        assertTrue(cmd.contains("session-start.js"), "default Posix hook must reference session-start.js");
        assertFalse(Files.list(hooksDir).findAny().isPresent(),
            "default (node) Posix hook must not write any files");
    }

    @Test
    void posix_hookCommand_shInstaller_copiesScriptNextToHooks(@TempDir Path hooksDir) throws Exception {
        // When the hook bootstraps via the sh installer (prod), the static script is
        // copied next to hooks.json — mirroring how Windows writes install-runtime.bat.
        String shHook = "sh \"${CLAUDE_PLUGIN_ROOT}/hooks/" + Os.INSTALL_SCRIPT_NAME + "\" shipsmooth 0.3.3";
        System.setProperty("plugin.hook.command", shHook);
        try {
            String cmd = Os.POSIX.hookCommand(hooksDir, "shipsmooth", "shipsmooth", "0.3.3");
            assertEquals(shHook, cmd, "Posix hook must return the sh invocation verbatim");

            Path script = hooksDir.resolve(Os.INSTALL_SCRIPT_NAME);
            assertTrue(Files.exists(script), Os.INSTALL_SCRIPT_NAME + " must be copied next to hooks.json");
            String body = Files.readString(script);
            assertTrue(body.startsWith("#!/bin/sh"), "installer must be a POSIX sh script");
            assertTrue(body.contains("curl"), "installer must download with curl");
            assertFalse(body.contains("session-start.js"), "installer must not reference the Node entry point");
        } finally {
            System.clearProperty("plugin.hook.command");
        }
    }

    @Test
    void posix_copyResource_missingResource_throws(@TempDir Path hooksDir) {
        Os.Posix posix = new Os.Posix();
        Path dest = hooksDir.resolve("nope.sh");
        IOException ex = assertThrows(IOException.class,
            () -> posix.copyResource("does-not-exist-on-classpath.sh", dest));
        assertTrue(ex.getMessage().contains("not found on classpath"),
            "missing bundled resource must raise a clear IOException");
    }

    @Test
    void from_windows_isWindows() {
        assertEquals(Os.WINDOWS, Os.from("windows"));
    }

    @Test
    void from_posix_isPosix() {
        assertEquals(Os.POSIX, Os.from("posix"));
    }

    @Test
    void fromPackagingTarget_win32_isWindows() {
        assertEquals(Os.WINDOWS, Os.fromPackagingTarget("win32-x64"));
    }

    @Test
    void fromPackagingTarget_linux_isPosix() {
        assertEquals(Os.POSIX, Os.fromPackagingTarget("linux-x64"));
    }

    @Test
    void fromPackagingTarget_macOs_isPosix() {
        assertEquals(Os.POSIX, Os.fromPackagingTarget("darwin-arm64"));
    }
}
