package io.bitken.ss.dist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ValidateRelease {

    public static void main(String[] args) throws IOException {
        String outputDirProp = System.getProperty("build.outputDir");
        String geminiDirProp = System.getProperty("build.gemini.outputDir");
        String codexDirProp = System.getProperty("build.codex.outputDir");
        String opencodeDirProp = System.getProperty("build.opencode.outputDir");
        if (outputDirProp == null) {
            System.err.println("Error: -Dbuild.outputDir=<dir> is required");
            System.exit(1);
        }
        Path outputDir = Path.of(outputDirProp);
        Path geminiDir = geminiDirProp != null ? Path.of(geminiDirProp) : null;
        Path codexDir = codexDirProp != null ? Path.of(codexDirProp) : null;
        Path opencodeDir = opencodeDirProp != null ? Path.of(opencodeDirProp) : null;
        validate(outputDir, geminiDir, codexDir, opencodeDir);
        System.out.println("ValidateRelease: all manifest checks passed.");
    }

    /** 3-arg overload (no opencode payload) — preserves existing call sites. */
    static void validate(Path outputDir, Path geminiDir, Path codexDir) throws IOException {
        validate(outputDir, geminiDir, codexDir, null);
    }

    static void validate(Path outputDir, Path geminiDir, Path codexDir, Path opencodeDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        validatePluginJson(mapper, outputDir.resolve(".claude-plugin/plugin.json"));
        validateMarketplaceJson(mapper, outputDir.resolve(".claude-plugin/marketplace.json"));
        if (geminiDir != null) {
            Path geminiManifest = geminiDir.resolve("gemini-extension.json");
            if (Files.exists(geminiManifest)) {
                validateGeminiExtensionJson(mapper, geminiManifest);
            }
        }
        if (codexDir != null) {
            validateCodexPayload(mapper, codexDir);
        }
        if (opencodeDir != null) {
            validateOpencodePayload(mapper, opencodeDir);
        }
    }

    /**
     * Validate ONLY the opencode payload (PublishRelease's per-host check). Unlike the
     * multi-host {@link #validate} path — which tolerates an unassembled payload — this
     * requires the manifest to be present, since the release just assembled it.
     */
    static void validateOpencode(Path opencodeDir) throws IOException {
        Path manifest = opencodeDir.resolve("package.json");
        if (!Files.exists(manifest)) {
            throw new IllegalStateException(opencodeDir + ": package.json missing — opencode payload not assembled");
        }
        validateOpencodePayload(new ObjectMapper(), opencodeDir);
    }

    /**
     * OpenCode payload (plan-86): a publishable npm package rooted at {@code package.json}
     * ({@code name}/{@code version}/{@code description}/{@code main}) with the plugin under
     * {@code plugin/} and skills under {@code skills/}. The {@code main} entry and the
     * compiled plugin file must actually exist, and no field may carry an unresolved
     * {@code ${...}} placeholder (the gemini-prod {@code ${plugin.name}} class of bug).
     */
    private static void validateOpencodePayload(ObjectMapper mapper, Path opencodeDir) throws IOException {
        Path manifest = opencodeDir.resolve("package.json");
        if (!Files.exists(manifest)) {
            return; // opencode payload not assembled for this release
        }
        JsonNode root = mapper.readTree(manifest.toFile());
        checkField(manifest, root, "name");
        checkField(manifest, root, "version");
        checkField(manifest, root, "description");
        String main = root.path("main").asText(null);
        checkField(manifest, root, "main", main);

        // The npm `main` must resolve to a real file (this is what OpenCode loads).
        Path mainFile = opencodeDir.resolve(main);
        if (!Files.exists(mainFile)) {
            throw new IllegalStateException(manifest + ": 'main' points at a missing file: " + main);
        }
        // The canonical skill must ship too.
        Path startSkill = opencodeDir.resolve("skills/start/SKILL.md");
        if (!Files.exists(startSkill)) {
            throw new IllegalStateException(opencodeDir + ": missing skills/start/SKILL.md");
        }
    }

    /**
     * Codex payload layout (plan-77): a marketplace root holding
     * .agents/plugins/marketplace.json + plugins/&lt;name&gt;/.codex-plugin/plugin.json.
     * The marketplace's plugins[0] uses source/policy/category (no description field),
     * so it cannot go through the Claude marketplace validator.
     */
    private static void validateCodexPayload(ObjectMapper mapper, Path codexDir) throws IOException {
        Path marketplace = codexDir.resolve(".agents/plugins/marketplace.json");
        if (!Files.exists(marketplace)) {
            return; // codex payload not assembled for this release
        }
        JsonNode root = mapper.readTree(marketplace.toFile());
        checkField(marketplace, root, "name");
        JsonNode plugins = root.path("plugins");
        if (!plugins.isArray() || plugins.isEmpty()) {
            throw new IllegalStateException(marketplace + ": 'plugins' array is missing or empty");
        }
        JsonNode first = plugins.get(0);
        String pluginName = first.path("name").asText(null);
        checkField(marketplace, first, "plugins[0].name", pluginName);
        checkField(marketplace, first, "plugins[0].source.path", first.path("source").path("path").asText(null));

        // The plugin manifest lives under plugins/<name>/.codex-plugin/plugin.json.
        Path pluginJson = codexDir.resolve("plugins").resolve(pluginName).resolve(".codex-plugin/plugin.json");
        JsonNode plugin = mapper.readTree(pluginJson.toFile());
        checkField(pluginJson, plugin, "name");
        checkField(pluginJson, plugin, "version");
        checkField(pluginJson, plugin, "description");
        checkField(pluginJson, plugin, "skills");
    }

    private static void validatePluginJson(ObjectMapper mapper, Path file) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        checkField(file, root, "name");
        checkField(file, root, "version");
        checkField(file, root, "description");
    }

    private static void validateMarketplaceJson(ObjectMapper mapper, Path file) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        checkField(file, root, "name");
        JsonNode plugins = root.path("plugins");
        if (!plugins.isArray() || plugins.isEmpty()) {
            throw new IllegalStateException(file.getFileName() + ": 'plugins' array is missing or empty");
        }
        JsonNode first = plugins.get(0);
        checkField(file, first, "plugins[0].name", first.path("name").asText(null));
        checkField(file, first, "plugins[0].description", first.path("description").asText(null));
    }

    private static void validateGeminiExtensionJson(ObjectMapper mapper, Path file) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        checkField(file, root, "name");
        checkField(file, root, "version");
        checkField(file, root, "description");
    }

    private static void checkField(Path file, JsonNode node, String fieldName) {
        checkField(file, node, fieldName, node.path(fieldName).asText(null));
    }

    private static void checkField(Path file, JsonNode node, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    file.getFileName() + ": field '" + fieldName + "' is missing or empty");
        }
        if (value.contains("${")) {
            throw new IllegalStateException(
                    file.getFileName() + ": field '" + fieldName + "' contains unresolved placeholder: " + value);
        }
    }
}
