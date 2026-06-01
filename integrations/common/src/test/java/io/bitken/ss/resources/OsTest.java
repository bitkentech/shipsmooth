package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

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
}
