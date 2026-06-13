package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Os is now pure facts only (launcher/java names, cliBinPath, from/fromPackagingTarget).
// The hook-command + companion-file emission tests moved to :plugin-resources
// (HookCommandRendererTest) along with the logic itself (plan-79 v5).
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
