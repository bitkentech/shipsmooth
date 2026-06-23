package io.bitken.ss.cli.conf.ds;

import java.util.List;

/** Root of {@code ~/.config/shipsmooth/shipsmooth.toml}. */
public final class StandaloneConfig {

    private List<ProjectEntry> projects = List.of();

    public List<ProjectEntry> getProjects() { return projects; }
    public void setProjects(List<ProjectEntry> projects) { this.projects = projects; }

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
