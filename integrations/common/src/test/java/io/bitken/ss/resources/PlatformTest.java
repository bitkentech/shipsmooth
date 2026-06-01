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
}
