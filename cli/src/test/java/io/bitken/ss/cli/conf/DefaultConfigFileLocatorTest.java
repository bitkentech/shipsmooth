package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConfigFileLocatorTest {

    // XDG_CONFIG_HOME wins on any platform when set
    @Test
    void xdgConfigHome_takesPrecedence() {
        Path result = DefaultConfigFileLocator.configFileFor(
                "/xdg/config", "C:\\Users\\me\\AppData\\Roaming", "/home/me");
        assertEquals(Path.of("/xdg/config", "shipsmooth", "shipsmooth.toml"), result);
    }

    // Windows: no XDG, %APPDATA% set → roaming app data
    @Test
    void appData_usedWhenNoXdg() {
        Path result = DefaultConfigFileLocator.configFileFor(
                null, "C:\\Users\\me\\AppData\\Roaming", "C:\\Users\\me");
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Roaming", "shipsmooth", "shipsmooth.toml"),
                result);
    }

    // POSIX: no XDG, no %APPDATA% → ~/.config
    @Test
    void posixDefault_whenNoXdgOrAppData() {
        Path result = DefaultConfigFileLocator.configFileFor(null, null, "/home/me");
        assertEquals(Path.of("/home/me", ".config", "shipsmooth", "shipsmooth.toml"), result);
    }

    // Blank env values are treated as unset
    @Test
    void blankEnvValues_fallThrough() {
        Path result = DefaultConfigFileLocator.configFileFor("  ", "", "/home/me");
        assertEquals(Path.of("/home/me", ".config", "shipsmooth", "shipsmooth.toml"), result);
    }

    // locate() reads the real environment; whatever config home it picks, the file
    // is always shipsmooth/shipsmooth.toml.
    @Test
    void locate_alwaysEndsWithShipsmoothToml() {
        Path result = new DefaultConfigFileLocator().locate();
        assertTrue(result.endsWith(Path.of("shipsmooth", "shipsmooth.toml")),
                "locate() must resolve to shipsmooth/shipsmooth.toml; was: " + result);
    }
}