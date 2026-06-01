package io.bitken.ss.resources;

public record PluginModel(
    String pluginName,
    String pluginVersion,
    String pluginDescription,
    String skillName,
    String cliBin,
    String skillFrontmatter,
    String skillFragmentDir,
    boolean gemini,
    Os os,
    Env env,
    String jlinkDir,
    String repoName
) {
    public PluginModel withSkill(String newSkillName, String newFrontmatter) {
        return new PluginModel(
            pluginName, pluginVersion, pluginDescription,
            newSkillName, cliBin, newFrontmatter, skillFragmentDir, gemini, os, env, jlinkDir, repoName
        );
    }

    public boolean hasJlinkDir() {
        return jlinkDir != null && !jlinkDir.isBlank();
    }

    public String skillName(String base) {
        return env.decorate(base);
    }

    public boolean isGemini() {
        return gemini;
    }
}
