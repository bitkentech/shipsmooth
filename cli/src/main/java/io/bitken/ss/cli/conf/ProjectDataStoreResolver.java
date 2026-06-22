package io.bitken.ss.cli.conf;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a {@link ProjectDataStore} from the user's {@code shipsmooth.toml}
 * (located by a {@link ConfigFileLocator}).
 *
 * <p>Matching is by the pair {@code (localPath, remoteUrl)}. No config file or no
 * matching entry both map to {@link ProjectDataStore.InRepo} (in-repo default mode).
 */
public final class ProjectDataStoreResolver {

    private final TomlMapper toml;
    private final ConfigFileLocator configFileLocator;

    public ProjectDataStoreResolver() {
        this(new DefaultConfigFileLocator());
    }

    /** Test seam — supply a locator that points at a fixed config file path. */
    ProjectDataStoreResolver(ConfigFileLocator configFileLocator) {
        this.configFileLocator = configFileLocator;
        toml = new TomlMapper();
    }

    /**
     * @param localPath canonical repo root ({@code git rev-parse --show-toplevel})
     * @param remoteUrl origin URL if present
     * @return {@link ProjectDataStore.Standalone} with the matched {@code stateDir}, or
     *         {@link ProjectDataStore.InRepo} when no entry matches (in-repo default)
     */
    public ProjectDataStore resolve(Path localPath, Optional<String> remoteUrl) {
        Path configFile = configFileLocator.locate();
        if (!Files.exists(configFile)) {
            LegacyDataTreeGuard.check(localPath);
            return new ProjectDataStore.InRepo(localPath);
        }
        return matchEntry(parseConfig(configFile), configFile, localPath, remoteUrl);
    }

    private StandaloneConfig parseConfig(Path configFile) {
        try {
            return toml.readValue(configFile.toFile(), StandaloneConfig.class);
        } catch (IOException e) {
            throw new StandaloneConfigException("Failed to parse " + configFile + ": " + e.getMessage(), e);
        }
    }

    private ProjectDataStore matchEntry(StandaloneConfig config, Path configFile,
                                        Path localPath, Optional<String> remoteUrl) {
        String localPathStr = localPath.toAbsolutePath().normalize().toString();

        for (StandaloneConfig.ProjectEntry entry : config.getProjects()) {
            if (!localPathStr.equals(normalisePath(entry.getLocalPath()))) {
                continue;
            }
            if (remoteUrl.isPresent() && entry.getRemoteUrl() != null
                    && !remoteUrl.get().equals(entry.getRemoteUrl())) {
                continue;
            }
            if (entry.getStateDir() == null || entry.getStateDir().isBlank()) {
                throw new StandaloneConfigException(
                        "Entry for localPath=" + localPathStr + " has no stateDir in " + configFile);
            }
            return new ProjectDataStore.Standalone(localPath,
                    Path.of(entry.getStateDir()).toAbsolutePath().normalize());
        }

        LegacyDataTreeGuard.check(localPath);
        return new ProjectDataStore.InRepo(localPath);
    }

    private static String normalisePath(String raw) {
        if (raw == null) return "";
        return Path.of(raw).toAbsolutePath().normalize().toString();
    }
}