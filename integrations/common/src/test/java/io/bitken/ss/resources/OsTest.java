package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals("${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-0.3.3/bin/shipsmooth", result);
    }

    @Test
    void posix_cliBinPath_devSubdirVariant() {
        String result = Os.POSIX.cliBinPath("shipsmooth", "0.3.3", "shipsmooth-dev");
        assertEquals("${XDG_CACHE_HOME:-~/.cache}/shipsmooth-dev/runtime-0.3.3/bin/shipsmooth", result);
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
    void posix_hookCommand_returnsSystemPropertyDefault(@TempDir Path hooksDir) throws Exception {
        String cmd = Os.POSIX.hookCommand(hooksDir, "shipsmooth", "shipsmooth", "0.3.3");
        assertTrue(cmd.contains("session-start.js"), "Posix hook must reference session-start.js");
        assertFalse(Files.list(hooksDir).findAny().isPresent(), "Posix hook must not write any files");
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
