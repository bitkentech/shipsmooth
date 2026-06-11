package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public class Target {

    private final SkillRenderer skillRenderer;
    private final HooksRenderer hooksRenderer;
    private final SessionStartConfigRenderer sessionStartConfigRenderer;
    private final boolean experimentalEnabled;

    Target(String platformProp, String osProp, String envProp,
           String basePluginName, String version, String description,
           String startBase, String frontmatter, String jlinkDir, String repoName,
           String outputDir, boolean experimentalEnabled) {
        Platform platform       = Platform.from(platformProp);
        Os os                   = Os.from(osProp);
        Env env                 = Env.from(envProp);
        guard(os, platform, env);
        String name             = env.decorate(basePluginName);
        String cacheSubdir      = platform.cacheSubdir(basePluginName, env);
        PluginModel baseModel   = new PluginModel(
            name, version, description,
            env.decorate(startBase),
            os.cliBinPath(basePluginName, version, cacheSubdir),
            frontmatter,
            platform.skillFragmentDir(),
            platform instanceof Platform.Gemini,
            os, env, jlinkDir,
            repoName != null ? repoName : name,
            experimentalEnabled
        );
        Path outDir             = Path.of(outputDir);
        ObjectMapper mapper     = new ObjectMapper();
        this.skillRenderer              = new SkillRenderer(baseModel, outDir, startBase);
        this.hooksRenderer              = new HooksRenderer(mapper, baseModel, outDir);
        this.sessionStartConfigRenderer = new SessionStartConfigRenderer(mapper, baseModel, outDir);
        this.experimentalEnabled        = experimentalEnabled;
    }

    public static void main(String[] args) throws IOException {
        new Target(
            System.getProperty("build.platform", "claude"),
            System.getProperty("build.os", "posix"),
            System.getProperty("build.env", "prod"),
            System.getProperty("plugin.base.name"),
            System.getProperty("plugin.version"),
            System.getProperty("plugin.description"),
            System.getProperty("plugin.skill.start.basename"),
            System.getProperty("skill.frontmatter", ""),
            System.getProperty("shipsmooth.jlink.dir", ""),
            System.getProperty("plugin.repo.name"),
            System.getProperty("build.outputDir"),
            Boolean.parseBoolean(System.getProperty("experimental.enabled", "false"))
        ).build();
    }

    void build() throws IOException {
        skillRenderer.renderBase();
        if (experimentalEnabled) {
            skillRenderer.renderExperimental();
        }
        hooksRenderer.write();
        sessionStartConfigRenderer.write();
    }

    static void guard(Os os, Platform platform, Env env) {
        if (os == Os.WINDOWS && platform != Platform.CLAUDE) {
            throw new IllegalArgumentException(
                "Windows is only supported with the Claude platform, got: " + platform.id());
        }
        if (os == Os.WINDOWS && env == Env.DEV) {
            throw new IllegalArgumentException("Windows + Dev environment is not supported");
        }
    }
}
