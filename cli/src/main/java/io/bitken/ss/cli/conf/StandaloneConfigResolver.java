package io.bitken.ss.cli.conf;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the standalone state-repo root from
 * {@code ~/.config/shipsmooth/ss-config.toml}.
 *
 * <p>Matches by the pair {@code (localPath, remoteUrl)}. Returns empty when no
 * config file exists or no entry matches — both map to in-repo default mode.
 */
public final class StandaloneConfigResolver {

    private static final TomlMapper TOML = new TomlMapper();

    private final Path configFile;

    public StandaloneConfigResolver() {
        this(defaultConfigFile());
    }

    /** Test seam — inject a custom config file path. */
    StandaloneConfigResolver(Path configFile) {
        this.configFile = configFile;
    }

    /**
     * @param localPath  canonical repo root ({@code git rev-parse --show-toplevel})
     * @param remoteUrl  origin URL if present
     * @return the {@code stateDir} path from the matching entry, or empty for in-repo mode
     */
    public Optional<Path> resolve(Path localPath, Optional<String> remoteUrl) {
        if (!Files.exists(configFile)) {
            return Optional.empty();
        }

        StandaloneConfig config;
        try {
            config = TOML.readValue(configFile.toFile(), StandaloneConfig.class);
        } catch (IOException e) {
            throw new StandaloneConfigException("Failed to parse " + configFile + ": " + e.getMessage(), e);
        }

        String localPathStr = localPath.toAbsolutePath().normalize().toString();

        for (StandaloneConfig.ProjectEntry entry : config.getProjects()) {
            if (!localPathStr.equals(normalisePath(entry.getLocalPath()))) {
                continue;
            }
            // localPath matches — check remoteUrl if both sides have one
            if (remoteUrl.isPresent() && entry.getRemoteUrl() != null
                    && !remoteUrl.get().equals(entry.getRemoteUrl())) {
                continue;
            }
            // match found
            if (entry.getStateDir() == null || entry.getStateDir().isBlank()) {
                throw new StandaloneConfigException(
                        "Entry for localPath=" + localPathStr + " has no stateDir in " + configFile);
            }
            return Optional.of(Path.of(entry.getStateDir()).toAbsolutePath().normalize());
        }

        return Optional.empty();
    }

    private static String normalisePath(String raw) {
        if (raw == null) return "";
        return Path.of(raw).toAbsolutePath().normalize().toString();
    }

    private static Path defaultConfigFile() {
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        Path configHome = (xdgConfig != null && !xdgConfig.isBlank())
                ? Path.of(xdgConfig)
                : Path.of(System.getProperty("user.home"), ".config");
        return configHome.resolve("shipsmooth/ss-config.toml");
    }
}
