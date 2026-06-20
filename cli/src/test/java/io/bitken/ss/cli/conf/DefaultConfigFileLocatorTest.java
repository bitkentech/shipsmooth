package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultConfigFileLocatorTest {

    // XDG_CONFIG_HOME wins on any platform when set
    @Test
    void xdgConfigHome_takesPrecedence() {
        Path result = DefaultConfigFileLocator.configFileFor(
                "/xdg/config", "C:\\Users\\me\\AppData\\Roaming", "/home/me");
        assertEquals(Path.of("/xdg/config", "shipsmooth", "ss-config.toml"), result);
    }

    // Windows: no XDG, %APPDATA% set → roaming app data
    @Test
    void appData_usedWhenNoXdg() {
        Path result = DefaultConfigFileLocator.configFileFor(
                null, "C:\\Users\\me\\AppData\\Roaming", "C:\\Users\\me");
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Roaming", "shipsmooth", "ss-config.toml"),
                result);
    }

    // POSIX: no XDG, no %APPDATA% → ~/.config
    @Test
    void posixDefault_whenNoXdgOrAppData() {
        Path result = DefaultConfigFileLocator.configFileFor(null, null, "/home/me");
        assertEquals(Path.of("/home/me", ".config", "shipsmooth", "ss-config.toml"), result);
    }

    // Blank env values are treated as unset
    @Test
    void blankEnvValues_fallThrough() {
        Path result = DefaultConfigFileLocator.configFileFor("  ", "", "/home/me");
        assertEquals(Path.of("/home/me", ".config", "shipsmooth", "ss-config.toml"), result);
    }
}