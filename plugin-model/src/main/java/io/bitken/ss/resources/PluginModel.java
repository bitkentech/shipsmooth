package io.bitken.ss.resources;

public record PluginModel(
    String pluginName,
    String pluginVersion,
    String pluginDescription,
    String skillName,
    String cliBin,
    String skillFrontmatter,
    String skillFragmentDir,
    String platformId,
    Os os,
    Env env,
    String jlinkDir,
    String repoName,
    boolean experimentalEnabled
) {
    public PluginModel withSkill(String newSkillName, String newFrontmatter) {
        return new PluginModel(
            pluginName, pluginVersion, pluginDescription,
            newSkillName, cliBin, newFrontmatter, skillFragmentDir, platformId, os, env, jlinkDir, repoName,
            experimentalEnabled
        );
    }

    public boolean hasJlinkDir() {
        return jlinkDir != null && !jlinkDir.isBlank();
    }

    public String skillName(String base) {
        return env.decorate(base);
    }

    public boolean isGemini() {
        return "gemini".equals(platformId);
    }

    public boolean isCodex() {
        return "codex".equals(platformId);
    }

    public boolean isOpencode() {
        return "opencode".equals(platformId);
    }
}
