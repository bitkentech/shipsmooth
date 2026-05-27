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
        if (outputDirProp == null) {
            System.err.println("Error: -Dbuild.outputDir=<dir> is required");
            System.exit(1);
        }
        Path outputDir = Path.of(outputDirProp);
        Path geminiDir = geminiDirProp != null ? Path.of(geminiDirProp) : null;
        validate(outputDir, geminiDir);
        System.out.println("ValidateRelease: all manifest checks passed.");
    }

    static void validate(Path outputDir, Path geminiDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        validatePluginJson(mapper, outputDir.resolve(".claude-plugin/plugin.json"));
        validateMarketplaceJson(mapper, outputDir.resolve(".claude-plugin/marketplace.json"));
        if (geminiDir != null) {
            Path geminiManifest = geminiDir.resolve("gemini-extension.json");
            if (Files.exists(geminiManifest)) {
                validateGeminiExtensionJson(mapper, geminiManifest);
            }
        }
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
