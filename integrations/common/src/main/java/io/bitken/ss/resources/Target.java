package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public record Target(Platform platform, Os os, Env env) {

    public static void main(String[] args) throws IOException {
        fromProperties().build();
    }

    private static Target fromProperties() {
        return Target.from(
            System.getProperty("build.platform", "claude"),
            System.getProperty("build.os", "posix"),
            System.getProperty("build.env", "prod")
        );
    }

    public static Target from(String platformProp, String osProp, String envProp) {
        Platform platform = Platform.from(platformProp);
        Os os = Os.from(osProp);
        if (os == Os.WINDOWS && platform != Platform.CLAUDE) {
            throw new IllegalArgumentException("Windows is only supported with the Claude platform, got: " + platformProp);
        }
        Env env = Env.from(envProp);
        if (os == Os.WINDOWS && env == Env.DEV) {
            throw new IllegalArgumentException("Windows + Dev environment is not supported");
        }
        return new Target(platform, os, env);
    }

    private void build() throws IOException {
        String basePluginName = System.getProperty("plugin.base.name");
        String startBase      = System.getProperty("plugin.skill.start.basename");
        PluginModel baseModel = buildPluginModel(
            basePluginName,
            System.getProperty("plugin.version"),
            System.getProperty("plugin.description"),
            startBase,
            System.getProperty("skill.frontmatter", ""),
            System.getProperty("shipsmooth.jlink.dir", ""),
            System.getProperty("plugin.repo.name")
        );
        Path outputDir = Path.of(System.getProperty("build.outputDir"));
        ObjectMapper mapper = new ObjectMapper();
        boolean experimentalEnabled = Boolean.parseBoolean(System.getProperty("experimental.enabled", "false"));

        SkillRenderer skills = new SkillRenderer(baseModel, outputDir, startBase);
        skills.renderBase();
        if (experimentalEnabled) {
            skills.renderExperimental();
        }
        new HooksRenderer(mapper, baseModel, outputDir).write();
        new SessionStartConfigRenderer(mapper, baseModel, outputDir).write();
    }

    public PluginModel buildPluginModel(
            String basePluginName, String version, String description,
            String startBase, String frontmatter, String jlinkDir, String repoName) {
        String name        = env.decorate(basePluginName);
        String cacheSubdir = platform.cacheSubdir(basePluginName, env);
        return new PluginModel(
            name,
            version,
            description,
            env.decorate(startBase),
            os.cliBinPath(basePluginName, version, cacheSubdir),
            frontmatter,
            skillFragmentDir(),
            platform instanceof Platform.Gemini,
            os,
            env,
            jlinkDir,
            repoName != null ? repoName : name
        );
    }

    public String cliBin(String pluginName, String version) {
        return os.cliBinPath(pluginName, version, platform.cacheSubdir(pluginName, env));
    }

    public String skillFragmentDir() {
        return platform.skillFragmentDir();
    }

    public String launcherFileName() {
        return os.launcherFileName();
    }
}
