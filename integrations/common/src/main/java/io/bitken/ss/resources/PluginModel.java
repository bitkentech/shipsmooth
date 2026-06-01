package io.bitken.ss.resources;

/**
 * The plugin's identity and layout: names, version, target, and the cache/runtime paths
 * derived from them. Knows how to assemble itself from build properties and answers questions
 * about its own fields so renderers don't reason about raw values.
 */
public record PluginModel(
    String pluginName,
    String pluginVersion,
    String pluginDescription,
    String skillName,
    String cliBin,
    String skillFrontmatter,
    Target target,
    String jlinkDir,
    String repoName
) {
    static PluginModel fromProperties() {
        String platformProp = System.getProperty("build.platform", "claude");
        String osProp       = System.getProperty("build.os", "posix");
        String envProp      = System.getProperty("build.env", "prod");
        Target target       = Target.from(platformProp, osProp, envProp);
        Env env             = target.env();

        String basePluginName = System.getProperty("plugin.base.name");
        String version        = System.getProperty("plugin.version");
        String name           = env.decorate(basePluginName);
        String cacheSubdir    = target.platform().cacheSubdir(basePluginName, env);
        String startBase      = System.getProperty("plugin.skill.start.basename");

        return new PluginModel(
            name,
            version,
            System.getProperty("plugin.description"),
            env.decorate(startBase),
            target.os().cliBinPath(basePluginName, version, cacheSubdir),
            System.getProperty("skill.frontmatter", ""),
            target,
            System.getProperty("shipsmooth.jlink.dir", ""),
            System.getProperty("plugin.repo.name", name)
        );
    }

    public PluginModel withSkill(String newSkillName, String newFrontmatter) {
        return new PluginModel(
            pluginName, pluginVersion, pluginDescription,
            newSkillName, cliBin, newFrontmatter, target, jlinkDir, repoName
        );
    }

    public boolean hasJlinkDir() {
        return jlinkDir != null && !jlinkDir.isBlank();
    }

    public String skillName(String base) {
        return target.env().decorate(base);
    }

    public boolean isGemini() {
        return target.platform() instanceof Platform.Gemini;
    }

    public String skillFragmentDir() {
        return target.skillFragmentDir();
    }
}
