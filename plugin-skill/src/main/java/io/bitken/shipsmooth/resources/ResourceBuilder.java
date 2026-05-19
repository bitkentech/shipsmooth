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
        String pluginVersion  = System.getProperty("plugin.version");
        String pluginDesc     = System.getProperty("plugin.description");
        String frontmatter    = System.getProperty("skill.frontmatter", "");
        String jlinkDir       = System.getProperty("shipsmooth.jlink.dir", "");
        String startBase      = System.getProperty("plugin.skill.start.basename");

        BuildProfile profile  = BuildProfile.fromProperties();
        String pluginName     = profile.pluginName();
        String skillName      = profile.skillName(startBase);
        // Shell expression mirrors resolveCache() in session-start.ts — keep in sync with base-workflow.jte.md
        String cliBin         = profile.cliBin(pluginVersion);

        PluginModel model = new PluginModel(
            pluginName, pluginVersion, pluginDesc,
            skillName, cliBin, frontmatter, profile.platform(), jlinkDir
        );

        boolean experimentalEnabled = Boolean.parseBoolean(System.getProperty("experimental.enabled", "false"));

        TemplateEngine engine = TemplateEngine.createPrecompiled(ContentType.Plain);

        Path skillDir = Path.of(buildOutputDir, "skills", skillName);
        Files.createDirectories(skillDir);
        renderTo(engine, "skills/start/SKILL.jte", model, skillDir.resolve("SKILL.md"));
        System.out.println("Rendered SKILL.md to " + skillDir.toAbsolutePath());

        if (!experimentalEnabled) {
            ObjectMapper mapper = new ObjectMapper();
            Path hooksDir = Path.of(buildOutputDir, "hooks");
            Files.createDirectories(hooksDir);
            writeHooksJson(mapper, model, hooksDir.resolve("hooks.json"));
            System.out.println("Written hooks.json to " + hooksDir.toAbsolutePath());
            Path distDir = Path.of(buildOutputDir, "dist");
            Files.createDirectories(distDir);
            writeSessionStartConfig(mapper, model, distDir.resolve("session-start-config.json"));
            System.out.println("Written session-start-config.json to " + distDir.toAbsolutePath());
            return;
        }

        // Second skill: the TLA-checked-ledger variant.
        String tlaSkillName = profile.skillName("experimental-" + startBase + "-tla");
        String tlaFrontmatter = frontmatter.isEmpty() ? "" : """
            ---
            name: %s
            description: Use when starting any task — applies the shipsmooth agent coding workflow with a TLA-checked content-addressed ledger.
            ---""".formatted(tlaSkillName);
        PluginModel tlaModel = new PluginModel(
            pluginName, pluginVersion, pluginDesc,
            tlaSkillName, cliBin, tlaFrontmatter, profile.platform(), jlinkDir
        );
        Path tlaSkillDir = Path.of(buildOutputDir, "skills", tlaSkillName);
        Files.createDirectories(tlaSkillDir);
        renderTo(engine, "skills/experimental/start-tla/SKILL.jte", tlaModel, tlaSkillDir.resolve("SKILL.md"));
        System.out.println("Rendered SKILL.md to " + tlaSkillDir.toAbsolutePath());

        // Third skill: the parallel-execution variant.
        String parallelSkillName = profile.skillName("experimental-" + startBase + "-parallel");
        String parallelFrontmatter = frontmatter.isEmpty() ? "" : """
            ---
            name: %s
            description: Use when starting any task — applies the shipsmooth agent coding workflow with parallel subagent execution and ledger-coordinated integration.
            ---""".formatted(parallelSkillName);
        PluginModel parallelModel = new PluginModel(
            pluginName, pluginVersion, pluginDesc,
            parallelSkillName, cliBin, parallelFrontmatter, profile.platform(), jlinkDir
        );
        Path parallelSkillDir = Path.of(buildOutputDir, "skills", parallelSkillName);
        Files.createDirectories(parallelSkillDir);
        renderTo(engine, "skills/experimental/start-parallel/SKILL.jte", parallelModel, parallelSkillDir.resolve("SKILL.md"));
        System.out.println("Rendered SKILL.md to " + parallelSkillDir.toAbsolutePath());

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
            .put("version", model.pluginVersion());
        if (model.jlinkDir() != null && !model.jlinkDir().isBlank()) {
            config.put("jlinkDir", model.jlinkDir());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), config);
    }
}