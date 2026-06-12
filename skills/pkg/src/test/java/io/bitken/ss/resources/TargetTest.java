package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetTest {

    @Test
    void unknownPlatform_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> Target.guard(Os.POSIX, Platform.from("unknown"), Env.PROD));
    }

    @Test
    void windows_gemini_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> Target.guard(Os.WINDOWS, Platform.GEMINI, Env.PROD));
    }

    @Test
    void windows_codex_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> Target.guard(Os.WINDOWS, Platform.CODEX, Env.PROD));
    }

    @Test
    void posix_codex_ok() {
        assertDoesNotThrow(() -> Target.guard(Os.POSIX, Platform.CODEX, Env.PROD));
    }

    @Test
    void windows_dev_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> Target.guard(Os.WINDOWS, Platform.CLAUDE, Env.DEV));
    }
}
