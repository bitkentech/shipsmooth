package io.bitken.ss.resources;

/**
 * The plugin's identity and layout: names, version, target platform, and the
 * cache/runtime paths derived from them. Knows how to assemble itself from build
 * properties and answers questions about its own fields (jlink presence, Windows
 * paths) so renderers don't reason about raw values.
 */
public record PluginModel(
    String pluginName,
    String pluginVersion,
    String pluginDescription,
    String skillName,
    String cliBin,
    String skillFrontmatter,
    String platform,
    String jlinkDir,
    String repoName
) {
    static PluginModel fromProperties(BuildProfile profile) {
        String version = System.getProperty("plugin.version");
        String name    = profile.pluginName();
        return new PluginModel(
            name,
            version,
            System.getProperty("plugin.description"),
            profile.skillName(System.getProperty("plugin.skill.start.basename")),
            // Shell expression mirrors resolveCache() in session-start.ts — keep in sync with base-workflow.jte.md
            profile.cliBin(version),
            System.getProperty("skill.frontmatter", ""),
            profile.platform(),
            System.getProperty("shipsmooth.jlink.dir", ""),
            // repo name drives the Claude Code plugin cache path — may differ from pluginName (e.g. Windows repo is shipsmooth-windows but plugin is shipsmooth)
            System.getProperty("plugin.repo.name", name)
        );
    }

    public PluginModel withSkill(String newSkillName, String newFrontmatter) {
        return new PluginModel(
            pluginName, pluginVersion, pluginDescription,
            newSkillName, cliBin, newFrontmatter, platform, jlinkDir, repoName
        );
    }

    public boolean isWindows() {
        return "windows".equals(platform);
    }

    public boolean hasJlinkDir() {
        return jlinkDir != null && !jlinkDir.isBlank();
    }

    /** Single source of truth for the Claude Code plugin-cache root on Windows. */
    public String windowsCacheRoot() {
        return "%USERPROFILE%\\.claude\\plugins\\cache\\bitkentech\\" + repoName + "\\" + pluginVersion;
    }

    public String windowsRuntimeDest() {
        return "%LOCALAPPDATA%\\" + pluginName + "\\" + pluginVersion + "\\runtime";
    }
}
