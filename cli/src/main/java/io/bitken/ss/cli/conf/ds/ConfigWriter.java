package io.bitken.ss.cli.conf.ds;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.bitken.ss.cli.conf.ConfigFileLocator;
import io.bitken.ss.cli.conf.DefaultConfigFileLocator;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes (upserts) a project entry into the user's {@code shipsmooth.toml}.
 *
 * <p>The counterpart to {@link ProjectDataStoreResolver}'s read path. Records a project's
 * chosen state location keyed on {@code (localPath, remoteUrl)}: a matching entry is
 * replaced (idempotent), otherwise a new one is appended. The config file and its parent
 * directory are created if absent. Paths are written verbatim — never hash-derived.
 */
public final class ConfigWriter {

    private final TomlMapper toml;
    private final ConfigFileLocator configFileLocator;

    public ConfigWriter() {
        this(new DefaultConfigFileLocator());
    }

    /** Inject a specific config-file locator (used by {@code store init} wiring and tests). */
    public ConfigWriter(ConfigFileLocator configFileLocator) {
        this(configFileLocator, new TomlMapper());
    }

    /** Inject both the locator and the mapper — used by tests to simulate a failed write. */
    ConfigWriter(ConfigFileLocator configFileLocator, TomlMapper toml) {
        this.configFileLocator = configFileLocator;
        this.toml = toml;
    }

    /** Upsert an external-mode entry recording the chosen {@code stateDir}. */
    public void writeExternal(Path localPath, Optional<String> remoteUrl, Path stateDir) throws IOException {
        StandaloneConfig.ProjectEntry entry = baseEntry(localPath, remoteUrl);
        entry.setMode("external");
        entry.setStateDir(stateDir.toAbsolutePath().normalize().toString());
        upsert(entry);
    }

    /** Upsert an in-repo-mode entry (no {@code stateDir}). */
    public void writeInRepo(Path localPath, Optional<String> remoteUrl) throws IOException {
        StandaloneConfig.ProjectEntry entry = baseEntry(localPath, remoteUrl);
        entry.setMode("in-repo");
        upsert(entry);
    }

    private static StandaloneConfig.ProjectEntry baseEntry(Path localPath, Optional<String> remoteUrl) {
        StandaloneConfig.ProjectEntry entry = new StandaloneConfig.ProjectEntry();
        entry.setLocalPath(localPath.toAbsolutePath().normalize().toString());
        remoteUrl.ifPresent(entry::setRemoteUrl);
        return entry;
    }

    private void upsert(StandaloneConfig.ProjectEntry entry) throws IOException {
        Path configFile = configFileLocator.locate();
        StandaloneConfig config = readOrEmpty(configFile);

        List<StandaloneConfig.ProjectEntry> entries = new ArrayList<>(config.getProjects());
        entries.removeIf(e -> sameProject(e, entry));
        entries.add(entry);
        config.setProjects(entries);

        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writeAtomically(configFile, config);
    }

    /**
     * Serialize to a sibling temp file first, then atomically move it into place. A failed
     * serialize (e.g. the JPMS reflection failure fixed in plan-87) leaves only the discarded
     * temp file behind — never a truncated 0-byte {@code shipsmooth.toml} that would wedge
     * every subsequent {@link ProjectDataStoreResolver#resolve}.
     */
    private void writeAtomically(Path configFile, StandaloneConfig config) throws IOException {
        Path dir = configFile.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, configFile.getFileName().toString(), ".tmp");
        try {
            toml.writeValue(tmp.toFile(), config);
            try {
                Files.move(tmp, configFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private StandaloneConfig readOrEmpty(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            return new StandaloneConfig();
        }
        return toml.readValue(configFile.toFile(), StandaloneConfig.class);
    }

    /**
     * Two entries describe the same project when their {@code (localPath, remoteUrl)} match.
     * {@code remoteUrl} is compared blank-insensitively because TOML round-tripping turns an
     * absent value into an empty string, which must still match a freshly-built {@code null}.
     */
    private static boolean sameProject(StandaloneConfig.ProjectEntry a, StandaloneConfig.ProjectEntry b) {
        return normalisePath(a.getLocalPath()).equals(normalisePath(b.getLocalPath()))
                && blank(a.getRemoteUrl()).equals(blank(b.getRemoteUrl()));
    }

    private static String normalisePath(String raw) {
        if (raw == null) return "";
        return Path.of(raw).toAbsolutePath().normalize().toString();
    }

    private static String blank(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
