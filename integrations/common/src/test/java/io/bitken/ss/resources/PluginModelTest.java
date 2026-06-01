package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginModelTest {

    private static PluginModel claudeProd() {
        Target target = Target.from("claude", "prod");
        return new PluginModel("shipsmooth", "0.3.10", "desc", "start",
            target.cliBin("shipsmooth", "0.3.10"), "", target, "", "shipsmooth");
    }

    private static PluginModel windowsProd() {
        Target target = Target.from("windows", "prod");
        return new PluginModel("shipsmooth", "0.3.10", "desc", "start",
            target.cliBin("shipsmooth", "0.3.10"), "", target, "", "shipsmooth-windows");
    }

    @Test
    void target_returnsPlatformAndOs() {
        PluginModel m = claudeProd();
        assertEquals(Platform.CLAUDE, m.target().platform());
        assertEquals(Os.POSIX, m.target().os());
    }

    @Test
    void windows_target_hasWindowsOs() {
        PluginModel m = windowsProd();
        assertEquals(Os.WINDOWS, m.target().os());
    }

    @Test
    void cliBin_posix_usesXdgCacheHome() {
        PluginModel m = claudeProd();
        assertTrue(m.cliBin().contains("XDG_CACHE_HOME"), "posix cliBin must use XDG_CACHE_HOME");
        assertTrue(m.cliBin().endsWith("bin/shipsmooth"), "posix launcher has no extension");
    }

    @Test
    void cliBin_windows_usesLocalAppDataAndCmd() {
        PluginModel m = windowsProd();
        assertTrue(m.cliBin().contains("LOCALAPPDATA"), "windows cliBin must use LOCALAPPDATA");
        assertTrue(m.cliBin().endsWith("shipsmooth.cmd"), "windows launcher must be .cmd not .bat");
    }

    @Test
    void hasJlinkDir_false_whenBlank() {
        assertFalse(claudeProd().hasJlinkDir());
    }
}
