package io.bitken.ss.cli.conf;

import java.nio.file.Path;

/**
 * Default {@link ConfigFileLocator}: locates {@code ss-config.toml} under the
 * platform's per-user config home.
 *
 * <p>Precedence: {@code XDG_CONFIG_HOME} (explicit override, any platform) →
 * {@code %APPDATA%} (Windows roaming app data) → {@code ~/.config} (POSIX default).
 */
public final class DefaultConfigFileLocator implements ConfigFileLocator {

    @Override
    public Path locate() {
        return configFileFor(
                System.getenv("XDG_CONFIG_HOME"),
                System.getenv("APPDATA"),
                System.getProperty("user.home"));
    }

    /** Pure config-home selection — exposed for testing the per-platform branches. */
    static Path configFileFor(String xdgConfigHome, String appData, String userHome) {
        Path configHome;
        if (xdgConfigHome != null && !xdgConfigHome.isBlank()) {
            configHome = Path.of(xdgConfigHome);
        } else if (appData != null && !appData.isBlank()) {
            configHome = Path.of(appData);
        } else {
            configHome = Path.of(userHome, ".config");
        }
        // TODO: file name and path hardcoded
        return configHome.resolve("shipsmooth/ss-config.toml");
    }
}