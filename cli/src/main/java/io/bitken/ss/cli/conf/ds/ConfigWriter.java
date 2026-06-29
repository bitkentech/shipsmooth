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
    private final ArrayOfTablesTomlEmitter emitter;
    private final String schemaLocation;

    /**
     * Production entry point: the schema {@code location} is the value baked into this build
     * ({@link SchemaConfig#SCHEMA_LOCATION}).
     */
    public ConfigWriter() {
        this(new DefaultConfigFileLocator());
    }

    /** Inject a specific config-file locator; emits the build's baked schema location. */
    public ConfigWriter(ConfigFileLocator configFileLocator) {
        this(configFileLocator, SchemaConfig.SCHEMA_LOCATION);
    }

    /**
     * Inject the locator and the schema {@code location} to emit. A {@code null} location
     * means the emitted {@code [toml-schema]} table carries {@code version} only — no
     * {@code location} key (spec-valid: {@code location} is optional).
     */
    public ConfigWriter(ConfigFileLocator configFileLocator, String schemaLocation) {
        this(configFileLocator, schemaLocation, new TomlMapper());
    }

    /** Inject the locator, schema location, and mapper; the emitter is the default. */
    ConfigWriter(ConfigFileLocator configFileLocator, String schemaLocation, TomlMapper toml) {
        this(configFileLocator, schemaLocation, toml, new ArrayOfTablesTomlEmitter());
    }

    /** Inject the emitter too — used by tests to simulate a failed serialize on the write path. */
    ConfigWriter(ConfigFileLocator configFileLocator, String schemaLocation, TomlMapper toml,
                 ArrayOfTablesTomlEmitter emitter) {
        this.configFileLocator = configFileLocator;
        this.schemaLocation = schemaLocation;
        this.toml = toml;
        this.emitter = emitter;
    }

    /** Upsert a {@code filesystem} entry recording the chosen {@code storageRoot}. */
    public void writeExternal(Path localPath, Optional<String> remoteUrl, Path storageRoot) throws IOException {
        StandaloneConfig.ProjectEntry entry = baseEntry(localPath, remoteUrl);
        entry.setStorageType("filesystem");
        entry.setStorageRoot(storageRoot.toAbsolutePath().normalize().toString());
        upsert(entry);
    }

    /** Upsert an {@code embedded} entry (no {@code storageRoot}). */
    public void writeInRepo(Path localPath, Optional<String> remoteUrl) throws IOException {
        StandaloneConfig.ProjectEntry entry = baseEntry(localPath, remoteUrl);
        entry.setStorageType("embedded");
        upsert(entry);
    }

    private StandaloneConfig schemaRef(StandaloneConfig config) {
        if (config.getTomlSchema() == null) {
            StandaloneConfig.TomlSchemaRef ref = new StandaloneConfig.TomlSchemaRef();
            ref.setVersion(SchemaConfig.SCHEMA_VERSION);
            // No default: when no location was injected, emit version only — the emitter
            // skips a null location (spec-valid; location is optional under [toml-schema]).
            if (schemaLocation != null) {
                ref.setLocation(schemaLocation);
            }
            config.setTomlSchema(ref);
        }
        return config;
    }

    private static StandaloneConfig.ProjectEntry baseEntry(Path localPath, Optional<String> remoteUrl) {
        StandaloneConfig.ProjectEntry entry = new StandaloneConfig.ProjectEntry();
        entry.setLocalPath(localPath.toAbsolutePath().normalize().toString());
        remoteUrl.ifPresent(entry::setRemoteUrl);
        return entry;
    }

    private void upsert(StandaloneConfig.ProjectEntry entry) throws IOException {
        Path configFile = configFileLocator.locate();
        StandaloneConfig config = schemaRef(readOrEmpty(configFile));

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
            Files.writeString(tmp, emitter.emit(config));
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
