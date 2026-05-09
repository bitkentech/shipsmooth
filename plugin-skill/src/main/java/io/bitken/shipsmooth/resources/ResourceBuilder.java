package io.bitken.shipsmooth.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ResourceBuilder {

    public static void main(String[] args) throws IOException {
        String buildOutputDir = System.getProperty("build.outputDir");
        String pluginName     = System.getProperty("plugin.name");
        String pluginVersion  = System.getProperty("plugin.version");
        String pluginDesc     = System.getProperty("plugin.description");
        String skillName      = System.getProperty("plugin.skillName");
        String frontmatter    = System.getProperty("skill.frontmatter", "");
        String cacheDir       = System.getProperty("shipsmooth.cache.dir.resolved", "");
        String platform       = System.getProperty("build.platform", "claude");
        String jlinkDir       = System.getProperty("shipsmooth.jlink.dir", "");
        String cliBin         = cacheDir + "/runtime-" + pluginVersion + "/bin/shipsmooth-tasks";

        PluginModel model = new PluginModel(
            pluginName, pluginVersion, pluginDesc,
            skillName, cliBin, frontmatter, cacheDir, platform, jlinkDir
        );

        TemplateEngine engine = TemplateEngine.createPrecompiled(ContentType.Plain);

        Path skillDir = Path.of(buildOutputDir, "skills", skillName);
        Files.createDirectories(skillDir);
        renderTo(engine, "skills/SKILL.jte", model, skillDir.resolve("SKILL.md"));
        System.out.println("Rendered SKILL.md to " + skillDir.toAbsolutePath());

        ObjectMapper mapper = new ObjectMapper();

        Path hooksDir = Path.of(buildOutputDir, "hooks");
        Files.createDirectories(hooksDir);
        writeHooksJson(mapper, model, hooksDir.resolve("hooks.json"));
        System.out.println("Written hooks.json to " + hooksDir.toAbsolutePath());

        Path distDir = Path.of(buildOutputDir, "dist");
        Files.createDirectories(distDir);
        writeSessionStartConfig(mapper, model, distDir.resolve("session-start-config.json"));
        System.out.println("Written session-start-config.json to " + distDir.toAbsolutePath());
    }

    static void renderTo(TemplateEngine engine, String templateName,
                         PluginModel model, Path outputFile) throws IOException {
        StringOutput out = new StringOutput();
        engine.render(templateName, model, out);
        Files.writeString(outputFile, out.toString());
    }

    static void writeHooksJson(ObjectMapper mapper, PluginModel model, Path outputFile) throws IOException {
        String command = System.getProperty("plugin.hook.command", "node \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"");

        ObjectNode hook = mapper.createObjectNode()
            .put("type", "command")
            .put("command", command);

        ArrayNode innerHooks = mapper.createArrayNode().add(hook);
        ObjectNode hookGroup = mapper.createObjectNode().set("hooks", innerHooks);
        ArrayNode sessionStart = mapper.createArrayNode().add(hookGroup);

        ObjectNode root = mapper.createObjectNode();
        root.putObject("hooks").set("SessionStart", sessionStart);

        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
    }

    static void writeSessionStartConfig(ObjectMapper mapper, PluginModel model, Path outputFile) throws IOException {
        ObjectNode config = mapper.createObjectNode()
            .put("version", model.pluginVersion())
            .put("cacheDir", model.cacheDir());
        if (model.jlinkDir() != null && !model.jlinkDir().isBlank()) {
            config.put("jlinkDir", model.jlinkDir());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), config);
    }
}