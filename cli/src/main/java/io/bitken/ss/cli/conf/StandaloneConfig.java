package io.bitken.ss.cli.conf;

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

        public String getRemoteUrl() { return remoteUrl; }
        public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }

        public String getLocalPath() { return localPath; }
        public void setLocalPath(String localPath) { this.localPath = localPath; }

        public String getStateDir() { return stateDir; }
        public void setStateDir(String stateDir) { this.stateDir = stateDir; }
    }
}
