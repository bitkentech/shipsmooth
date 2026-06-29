package io.bitken.ss.cli.conf.ds;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Root of {@code ~/.config/shipsmooth/shipsmooth.toml}. */
public final class StandaloneConfig {

    private TomlSchemaRef tomlSchema;
    private List<ProjectEntry> projects = List.of();

    @JsonProperty("toml-schema")
    public TomlSchemaRef getTomlSchema() { return tomlSchema; }
    public void setTomlSchema(TomlSchemaRef tomlSchema) { this.tomlSchema = tomlSchema; }

    public List<ProjectEntry> getProjects() { return projects; }
    public void setProjects(List<ProjectEntry> projects) { this.projects = projects; }

    /**
     * The {@code [toml-schema]} table — a reference to the TOML Schema definition for
     * {@code shipsmooth.toml}. Jackson deserializes this automatically from the config file;
     * the emitter writes it.
     */
    public static final class TomlSchemaRef {
        private String version;
        private String location;

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    public static final class ProjectEntry {
        private String remoteUrl;
        private String localPath;
        private String storageRoot;
        private String storageType;

        public String getRemoteUrl() { return remoteUrl; }
        public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }

        public String getLocalPath() { return localPath; }
        public void setLocalPath(String localPath) { this.localPath = localPath; }

        /**
         * The {@code filesystem} backend's location — the root of its state tree. Absent for
         * {@code embedded} entries (their state lives in the repo's {@code .shipsmooth/}). It
         * is a {@code storageType}-specific key: other backends carry their own location keys.
         */
        public String getStorageRoot() { return storageRoot; }
        public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }

        /**
         * Which storage backend the project uses: {@code "embedded"} (state inside the repo's
         * {@code .shipsmooth/}) or {@code "filesystem"} (state under {@code storageRoot}).
         * Embedded entries need no {@code storageRoot}; filesystem entries require one. An
         * entry must express exactly one valid combination; anything else is treated as a
         * malformed entry by the resolver.
         */
        public String getStorageType() { return storageType; }
        public void setStorageType(String storageType) { this.storageType = storageType; }
    }
}
