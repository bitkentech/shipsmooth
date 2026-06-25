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
        private String stateDir;
        private String mode;

        public String getRemoteUrl() { return remoteUrl; }
        public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }

        public String getLocalPath() { return localPath; }
        public void setLocalPath(String localPath) { this.localPath = localPath; }

        public String getStateDir() { return stateDir; }
        public void setStateDir(String stateDir) { this.stateDir = stateDir; }

        /**
         * {@code "in-repo"} or {@code "external"}. In-repo entries need no {@code stateDir};
         * external entries require one. An entry must express exactly one valid combination;
         * anything else is treated as a malformed entry by the resolver. May be absent for
         * back-compat with external-only entries that carry only a {@code stateDir}.
         */
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }
}
