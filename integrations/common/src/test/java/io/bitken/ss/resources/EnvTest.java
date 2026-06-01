package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnvTest {

    @Test
    void prod_suffix_isEmpty() {
        assertEquals("", Env.PROD.suffix());
    }

    @Test
    void dev_suffix_isDevDash() {
        assertEquals("-dev", Env.DEV.suffix());
    }

    @Test
    void prod_decorate_returnsBase() {
        assertEquals("shipsmooth", Env.PROD.decorate("shipsmooth"));
    }

    @Test
    void dev_decorate_appendsDevSuffix() {
        assertEquals("shipsmooth-dev", Env.DEV.decorate("shipsmooth"));
    }

    @Test
    void prod_isDev_false() {
        assertFalse(Env.PROD.isDev());
    }

    @Test
    void dev_isDev_true() {
        assertTrue(Env.DEV.isDev());
    }

    @Test
    void from_dev_isDev() {
        assertEquals(Env.DEV, Env.from("dev"));
    }

    @Test
    void from_prod_isProd() {
        assertEquals(Env.PROD, Env.from("prod"));
    }

    @Test
    void from_unknown_defaultsToProd() {
        assertEquals(Env.PROD, Env.from("anything-else"));
    }
}
