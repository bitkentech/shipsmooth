package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetTest {

    // --- five real variants ---

    @Test
    void claude_prod_isClaude_posix_prod() {
        Target t = Target.from("claude", "prod");
        assertEquals(Platform.CLAUDE, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.PROD, t.env());
    }

    @Test
    void claude_dev_isClaude_posix_dev() {
        Target t = Target.from("claude", "dev");
        assertEquals(Platform.CLAUDE, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.DEV, t.env());
    }

    @Test
    void windows_prod_isClaude_windows_prod() {
        // windows == claude-on-windows, not a third platform
        Target t = Target.from("windows", "prod");
        assertEquals(Platform.CLAUDE, t.platform());
        assertEquals(Os.WINDOWS, t.os());
        assertEquals(Env.PROD, t.env());
    }

    @Test
    void gemini_prod_isGemini_posix_prod() {
        Target t = Target.from("gemini", "prod");
        assertEquals(Platform.GEMINI, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.PROD, t.env());
    }

    @Test
    void gemini_dev_isGemini_posix_dev() {
        Target t = Target.from("gemini", "dev");
        assertEquals(Platform.GEMINI, t.platform());
        assertEquals(Os.POSIX, t.os());
        assertEquals(Env.DEV, t.env());
    }

    // --- illegal combinations ---

    @Test
    void unknownPlatform_throws() {
        assertThrows(IllegalArgumentException.class, () -> Target.from("unknown", "prod"));
    }

    // gemini+windows is unrepresentable — there is no path through Target.from() that constructs it
    @Test
    void gemini_from_always_yieldsPosix() {
        Target t = Target.from("gemini", "prod");
        assertEquals(Os.POSIX, t.os(), "gemini targets are always Posix; windows variant is inaccessible");
    }

    // --- convenience delegators ---

    @Test
    void cliBin_posix_prod() {
        Target t = Target.from("claude", "prod");
        assertEquals(
            "${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-0.3.3/bin/shipsmooth",
            t.cliBin("shipsmooth", "0.3.3")
        );
    }

    @Test
    void cliBin_windows_prod() {
        Target t = Target.from("windows", "prod");
        assertEquals(
            "%LOCALAPPDATA%\\shipsmooth\\0.3.10\\runtime\\bin\\shipsmooth.cmd",
            t.cliBin("shipsmooth", "0.3.10")
        );
    }

    @Test
    void skillFragmentDir_claude() {
        assertEquals("start/claude", Target.from("claude", "prod").skillFragmentDir());
    }

    @Test
    void skillFragmentDir_gemini() {
        assertEquals("start/gemini", Target.from("gemini", "prod").skillFragmentDir());
    }

    @Test
    void launcherFileName_posix() {
        assertEquals("shipsmooth", Target.from("claude", "prod").launcherFileName());
    }

    @Test
    void launcherFileName_windows() {
        assertEquals("shipsmooth.cmd", Target.from("windows", "prod").launcherFileName());
    }
}
