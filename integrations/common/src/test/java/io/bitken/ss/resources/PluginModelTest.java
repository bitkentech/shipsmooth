package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginModelTest {

    private static PluginModel claudeProd() {
        return new PluginModel(
            "shipsmooth", "0.3.10", "desc", "start",
            Os.POSIX.cliBinPath("shipsmooth", "0.3.10", Platform.CLAUDE.cacheSubdir("shipsmooth", Env.PROD)),
            "", Platform.CLAUDE.skillFragmentDir(), false,
            Os.POSIX, Env.PROD, "", null
        );
    }

    private static PluginModel windowsProd() {
        return new PluginModel(
            "shipsmooth", "0.3.10", "desc", "start",
            Os.WINDOWS.cliBinPath("shipsmooth", "0.3.10", Platform.CLAUDE.cacheSubdir("shipsmooth", Env.PROD)),
            "", Platform.CLAUDE.skillFragmentDir(), false,
            Os.WINDOWS, Env.PROD, "", "shipsmooth-windows"
        );
    }

    @Test
    void posix_os_isCorrect() {
        assertEquals(Os.POSIX, claudeProd().os());
    }

    @Test
    void windows_os_isCorrect() {
        assertEquals(Os.WINDOWS, windowsProd().os());
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

    @Test
    void claude_isGemini_false() {
        assertFalse(claudeProd().isGemini());
    }

    @Test
    void gemini_isGemini_true() {
        PluginModel m = new PluginModel(
            "shipsmooth", "0.3.10", "desc", "start",
            Os.POSIX.cliBinPath("shipsmooth", "0.3.10", Platform.GEMINI.cacheSubdir("shipsmooth", Env.PROD)),
            "", Platform.GEMINI.skillFragmentDir(), true,
            Os.POSIX, Env.PROD, "", null
        );
        assertTrue(m.isGemini());
    }

    @Test
    void skillFragmentDir_claude_isStartClaude() {
        assertEquals("start/claude", claudeProd().skillFragmentDir());
    }
}
