package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformTest {

    @Test
    void claude_id_isClaude() {
        assertEquals("claude", Platform.CLAUDE.id());
    }

    @Test
    void gemini_id_isGemini() {
        assertEquals("gemini", Platform.GEMINI.id());
    }

    @Test
    void claude_skillFragmentDir_isStartClaude() {
        assertEquals("start/claude", Platform.CLAUDE.skillFragmentDir());
    }

    @Test
    void gemini_skillFragmentDir_isStartGemini() {
        assertEquals("start/gemini", Platform.GEMINI.skillFragmentDir());
    }

    @Test
    void codex_id_isCodex() {
        assertEquals("codex", Platform.CODEX.id());
    }

    @Test
    void codex_skillFragmentDir_isStartCodex() {
        assertEquals("start/codex", Platform.CODEX.skillFragmentDir());
    }

    @Test
    void from_codex_resolvesCodex() {
        assertEquals(Platform.CODEX, Platform.from("codex"));
    }

    @Test
    void from_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> Platform.from("nope"));
    }

    @Test
    void codex_prod_cacheSubdir_hasNoSuffix() {
        assertEquals("shipsmooth", Platform.CODEX.cacheSubdir("shipsmooth", Env.PROD));
    }

    @Test
    void codex_dev_cacheSubdir_hasDevSuffix() {
        assertEquals("shipsmooth-dev", Platform.CODEX.cacheSubdir("shipsmooth", Env.DEV));
    }

    @Test
    void claude_prod_cacheSubdir_hasNoSuffix() {
        assertEquals("shipsmooth", Platform.CLAUDE.cacheSubdir("shipsmooth", Env.PROD));
    }

    @Test
    void claude_dev_cacheSubdir_hasDevSuffix() {
        assertEquals("shipsmooth-dev", Platform.CLAUDE.cacheSubdir("shipsmooth", Env.DEV));
    }

    @Test
    void gemini_prod_cacheSubdir_hasNoSuffix() {
        assertEquals("shipsmooth", Platform.GEMINI.cacheSubdir("shipsmooth", Env.PROD));
    }

    @Test
    void gemini_dev_cacheSubdir_hasDevSuffix() {
        assertEquals("shipsmooth-dev", Platform.GEMINI.cacheSubdir("shipsmooth", Env.DEV));
    }

    @Test
    void opencode_id_isOpencode() {
        assertEquals("opencode", Platform.OPENCODE.id());
    }

    @Test
    void opencode_skillFragmentDir_isStartOpencode() {
        assertEquals("start/opencode", Platform.OPENCODE.skillFragmentDir());
    }

    @Test
    void from_opencode_resolvesOpencode() {
        assertEquals(Platform.OPENCODE, Platform.from("opencode"));
    }

    @Test
    void opencode_prod_cacheSubdir_hasNoSuffix() {
        assertEquals("shipsmooth", Platform.OPENCODE.cacheSubdir("shipsmooth", Env.PROD));
    }

    @Test
    void opencode_dev_cacheSubdir_hasDevSuffix() {
        assertEquals("shipsmooth-dev", Platform.OPENCODE.cacheSubdir("shipsmooth", Env.DEV));
    }

    // emitsHooksJson capability (plan-86 Task 2): hook-based hosts emit hooks.json;
    // OpenCode does not.
    @Test
    void claude_emitsHooksJson_true() {
        assertTrue(Platform.CLAUDE.emitsHooksJson());
    }

    @Test
    void gemini_emitsHooksJson_true() {
        assertTrue(Platform.GEMINI.emitsHooksJson());
    }

    @Test
    void codex_emitsHooksJson_true() {
        assertTrue(Platform.CODEX.emitsHooksJson());
    }

    @Test
    void opencode_emitsHooksJson_false() {
        assertFalse(Platform.OPENCODE.emitsHooksJson());
    }
}
