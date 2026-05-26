package io.bitken.shipsmooth.resources;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildProfileTest {

    @Test
    void prodProfile_isDev_false() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertFalse(p.isDev());
    }

    @Test
    void devProfile_isDev_true() {
        var p = new BuildProfile("claude", "dev", "shipsmooth");
        assertTrue(p.isDev());
    }

    @Test
    void prodProfile_pluginName_hasNoSuffix() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertEquals("shipsmooth", p.pluginName());
    }

    @Test
    void devProfile_pluginName_hasDevSuffix() {
        var p = new BuildProfile("claude", "dev", "shipsmooth");
        assertEquals("shipsmooth-dev", p.pluginName());
    }

    @Test
    void prodProfile_skillName_hasNoSuffix() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertEquals("start", p.skillName("start"));
    }

    @Test
    void devProfile_skillName_hasDevSuffix() {
        var p = new BuildProfile("claude", "dev", "shipsmooth");
        assertEquals("start-dev", p.skillName("start"));
    }

    @Test
    void prodProfile_cacheSubdir_hasNoSuffix() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertEquals("shipsmooth", p.cacheSubdir());
    }

    @Test
    void devProfile_cacheSubdir_hasDevSuffix() {
        var p = new BuildProfile("claude", "dev", "shipsmooth");
        assertEquals("shipsmooth-dev", p.cacheSubdir());
    }

    @Test
    void prodProfile_cliBin_usesShipsmooth() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertEquals(
            "${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-0.3.3/bin/shipsmooth-tasks",
            p.cliBin("0.3.3")
        );
    }

    @Test
    void devProfile_cliBin_usesShipsmoothDev() {
        var p = new BuildProfile("claude", "dev", "shipsmooth");
        assertEquals(
            "${XDG_CACHE_HOME:-~/.cache}/shipsmooth-dev/runtime-0.3.3/bin/shipsmooth-tasks",
            p.cliBin("0.3.3")
        );
    }

    @Test
    void geminiPlatform_isGemini_true() {
        var p = new BuildProfile("gemini", "prod", "shipsmooth");
        assertTrue(p.isGemini());
    }

    @Test
    void claudePlatform_isGemini_false() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertFalse(p.isGemini());
    }

    @Test
    void windowsPlatform_isWindows_true() {
        var p = new BuildProfile("windows", "prod", "shipsmooth");
        assertTrue(p.isWindows());
    }

    @Test
    void claudePlatform_isWindows_false() {
        var p = new BuildProfile("claude", "prod", "shipsmooth");
        assertFalse(p.isWindows());
    }

    @Test
    void windowsProfile_cliBin_usesLocalAppData() {
        var p = new BuildProfile("windows", "prod", "shipsmooth");
        assertEquals(
            "%LOCALAPPDATA%\\shipsmooth\\0.3.10\\runtime\\bin\\shipsmooth-tasks.bat",
            p.cliBin("0.3.10")
        );
    }

    @Test
    void pluginBaseNameFlows_throughAllDerivedNames() {
        var p = new BuildProfile("claude", "dev", "myplugin");
        assertEquals("myplugin-dev", p.pluginName());
        assertEquals("myplugin-dev", p.cacheSubdir());
        assertTrue(p.cliBin("1.0").contains("myplugin-dev"));
    }
}