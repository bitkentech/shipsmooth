package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetTest {

    // --- five real variants ---

    @Test
    void claude_prod_isClaude_posix_prod() {
        Target t = Target.from("claude", "posix", "prod");
        assertEquals(Platform.CLAUDE, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.PROD, t.env());
    }

    @Test
    void claude_dev_isClaude_posix_dev() {
        Target t = Target.from("claude", "posix", "dev");
        assertEquals(Platform.CLAUDE, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.DEV, t.env());
    }

    @Test
    void claude_windows_prod_isClaude_windows_prod() {
        Target t = Target.from("claude", "windows", "prod");
        assertEquals(Platform.CLAUDE, t.platform());
        assertEquals(Os.WINDOWS, t.os());
        assertEquals(Env.PROD, t.env());
    }

    @Test
    void gemini_prod_isGemini_posix_prod() {
        Target t = Target.from("gemini", "posix", "prod");
        assertEquals(Platform.GEMINI, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.PROD, t.env());
    }

    @Test
    void gemini_dev_isGemini_posix_dev() {
        Target t = Target.from("gemini", "posix", "dev");
        assertEquals(Platform.GEMINI, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.DEV, t.env());
    }

    // --- illegal combinations ---

    @Test
    void unknownPlatform_throws() {
        assertThrows(IllegalArgumentException.class, () -> Target.from("unknown", "posix", "prod"));
    }

    @Test
    void windows_gemini_throws() {
        assertThrows(IllegalArgumentException.class, () -> Target.from("gemini", "windows", "prod"));
    }

    @Test
    void windows_dev_throws() {
        assertThrows(IllegalArgumentException.class, () -> Target.from("claude", "windows", "dev"));
    }

    // --- convenience delegators ---

    @Test
    void cliBin_posix_prod() {
        Target t = Target.from("claude", "posix", "prod");
        assertEquals(
            "${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-0.3.3/bin/shipsmooth",
            t.cliBin("shipsmooth", "0.3.3")
        );
    }

    @Test
    void cliBin_windows_prod() {
        Target t = Target.from("claude", "windows", "prod");
        assertEquals(
            "%LOCALAPPDATA%\\shipsmooth\\0.3.10\\runtime\\bin\\shipsmooth.cmd",
            t.cliBin("shipsmooth", "0.3.10")
        );
    }

    @Test
    void skillFragmentDir_claude() {
        assertEquals("start/claude", Target.from("claude", "posix", "prod").skillFragmentDir());
    }

    @Test
    void skillFragmentDir_gemini() {
        assertEquals("start/gemini", Target.from("gemini", "posix", "prod").skillFragmentDir());
    }

    @Test
    void launcherFileName_posix() {
        assertEquals("shipsmooth", Target.from("claude", "posix", "prod").launcherFileName());
    }

    @Test
    void launcherFileName_windows() {
        assertEquals("shipsmooth.cmd", Target.from("claude", "windows", "prod").launcherFileName());
    }
}
