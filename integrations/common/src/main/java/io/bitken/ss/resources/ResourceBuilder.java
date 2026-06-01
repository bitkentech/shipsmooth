package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Coordinates the plugin build: renders the SKILL.md files, then hooks.json,
 * then session-start-config.json. Delegates each concern to a dedicated renderer.
 */
public class ResourceBuilder {

    private final SkillRenderer skills;
    private final HooksRenderer hooks;
    private final SessionStartConfigRenderer config;
    private final boolean experimentalEnabled;

    ResourceBuilder(SkillRenderer skills, HooksRenderer hooks,
                    SessionStartConfigRenderer config, boolean experimentalEnabled) {
        this.skills = skills;
        this.hooks = hooks;
        this.config = config;
        this.experimentalEnabled = experimentalEnabled;
    }

    public static void main(String[] args) throws IOException {
        fromProperties().build();
    }

    void build() throws IOException {
        skills.renderBase();
        if (experimentalEnabled) {
            skills.renderExperimental();
        }
        hooks.write();
        config.write();
    }

    private static ResourceBuilder fromProperties() {
        PluginModel baseModel = PluginModel.fromProperties();
        Path outputDir = Path.of(System.getProperty("build.outputDir"));
        String startBase = System.getProperty("plugin.skill.start.basename");
        ObjectMapper mapper = new ObjectMapper();

        return new ResourceBuilder(
            new SkillRenderer(baseModel, outputDir, startBase),
            new HooksRenderer(mapper, baseModel, baseModel.target().os(), outputDir),
            new SessionStartConfigRenderer(mapper, baseModel, outputDir),
            Boolean.parseBoolean(System.getProperty("experimental.enabled", "false"))
        );
    }
}
