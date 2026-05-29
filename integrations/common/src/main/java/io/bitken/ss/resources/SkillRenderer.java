package io.bitken.ss.resources;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders the plugin's SKILL.md files from JTE templates — the base skill plus,
 * when experimental builds are enabled, the experimental variants.
 */
class SkillRenderer {

    private final TemplateEngine engine;
    private final BuildProfile profile;
    private final PluginModel baseModel;
    private final Path outputDir;
    private final String startBase;

    SkillRenderer(BuildProfile profile, PluginModel baseModel, Path outputDir, String startBase) {
        this.engine = TemplateEngine.createPrecompiled(ContentType.Plain);
        this.profile = profile;
        this.baseModel = baseModel;
        this.outputDir = outputDir;
        this.startBase = startBase;
    }

    void renderBase() throws IOException {
        renderSkill("skills/start/SKILL.jte", baseModel);
    }

    void renderExperimental() throws IOException {
        for (SkillVariant variant : experimentalVariants()) {
            renderSkill(variant.template(), baseModel.withSkill(variant.skillName(), variant.frontmatter()));
        }
    }

    private List<SkillVariant> experimentalVariants() {
        return List.of(
            skillVariant("skills/experimental/start-tla/SKILL.jte", "experimental-" + startBase + "-tla",
                "Use when starting any task — applies the shipsmooth agent coding workflow with a TLA-checked content-addressed ledger."),
            skillVariant("skills/experimental/start-parallel/SKILL.jte", "experimental-" + startBase + "-parallel",
                "Use when starting any task — applies the shipsmooth agent coding workflow with parallel subagent execution and ledger-coordinated integration."),
            skillVariant("skills/experimental/refine/SKILL.jte", "experimental-refine",
                "Improves the quality of code generation, or of already generated code")
        );
    }

    private SkillVariant skillVariant(String template, String baseName, String description) {
        String skillName = profile.skillName(baseName);
        return new SkillVariant(template, skillName, frontmatter(skillName, description));
    }

    private String frontmatter(String skillName, String description) {
        if (baseModel.skillFrontmatter().isEmpty()) {
            return "";
        }
        return """
            ---
            name: %s
            description: %s
            ---""".formatted(skillName, description);
    }

    private void renderSkill(String template, PluginModel model) throws IOException {
        Path skillDir = outputDir.resolve(Path.of("skills", model.skillName()));
        Files.createDirectories(skillDir);
        StringOutput out = new StringOutput();
        engine.render(template, model, out);
        Files.writeString(skillDir.resolve("SKILL.md"), out.toString());
        System.out.println("Rendered SKILL.md to " + skillDir.toAbsolutePath());
    }

    private record SkillVariant(String template, String skillName, String frontmatter) {
    }
}
