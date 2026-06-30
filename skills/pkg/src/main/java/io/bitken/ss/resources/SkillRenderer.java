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
 *
 * Public (with a public ctor + render methods) because Target, its only caller,
 * lives in :plugin-resources — a different module, so package-private access does
 * not reach across the module boundary even though the package name matches
 * (plan-79 Task 2).
 */
public class SkillRenderer {

    private final TemplateEngine engine;
    private final PluginModel baseModel;
    private final Path outputDir;

    public SkillRenderer(PluginModel baseModel, Path outputDir) {
        this.engine = TemplateEngine.createPrecompiled(ContentType.Plain);
        this.baseModel = baseModel;
        this.outputDir = outputDir;
    }

    public void renderBase() throws IOException {
        renderSkill("start/SKILL.jte", baseModel);
        renderReferences();
    }

    // Progressive disclosure (plan-96 Task 3): the start skill keeps a compact core in
    // SKILL.md and defers reference-only material to sibling files under reference/. The
    // core points to each by its relative path (reference/<name>.md), which the agent
    // reads on demand — portable across every host's skill layout (no host-specific
    // skill-dir variable needed).
    private void renderReferences() throws IOException {
        for (String ref : List.of(
                "audit-trail", "git-tagging", "first-run-handshake",
                "phase0-worked-example", "plan-closeout")) {
            renderReference(ref);
        }
    }

    private void renderReference(String name) throws IOException {
        Path refDir = outputDir.resolve(Path.of("skills", baseModel.skillName(), "reference"));
        Files.createDirectories(refDir);
        StringOutput out = new StringOutput();
        engine.render("start/reference/" + name + ".jte", baseModel, out);
        Files.writeString(refDir.resolve(name + ".md"), out.toString());
        System.out.println("Rendered reference " + name + ".md to " + refDir.toAbsolutePath());
    }

    public void renderExperimental() throws IOException {
        for (SkillVariant variant : experimentalVariants()) {
            renderSkill(variant.template(), baseModel.withSkill(variant.skillName(), variant.frontmatter()));
        }
    }

    private List<SkillVariant> experimentalVariants() {
        return List.of(
            skillVariant("experimental/refine/SKILL.jte", "experimental-refine",
                "Improves the quality of code generation, or of already generated code")
        );
    }

    private SkillVariant skillVariant(String template, String baseName, String description) {
        String skillName = baseModel.skillName(baseName);
        return new SkillVariant(template, skillName, frontmatter(skillName, description));
    }

    private String frontmatter(String skillName, String description) {
        if (baseModel.skillFrontmatter().isEmpty()) {
            return "";
        }
        // Claude Code drops the plugin namespace prefix from any skill whose SKILL.md
        // carries a `name:` (anthropics/claude-code#22063). To keep the experimental
        // skills namespaced (/shipsmooth:experimental-refine, not bare /experimental-refine),
        // omit `name:` for the claude host and let Claude derive it from the skill dir.
        // The other hosts namespace by plugin already and don't strip on `name:`, so they
        // keep the field — mirrors the start skill's claudeFrontmatter() in build.gradle.kts.
        if (baseModel.isClaude()) {
            return """
                ---
                description: %s
                ---""".formatted(description);
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
