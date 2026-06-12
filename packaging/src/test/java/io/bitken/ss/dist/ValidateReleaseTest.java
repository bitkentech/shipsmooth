package io.bitken.ss.dist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ValidateReleaseTest {

    @TempDir
    Path tempDir;

    private Path claudePlugin() throws IOException {
        Path dir = tempDir.resolve(".claude-plugin");
        Files.createDirectories(dir);
        return dir;
    }

    private void writePluginJson(Path dir, String name, String version, String description) throws IOException {
        Files.writeString(dir.resolve("plugin.json"), """
                {
                  "name": "%s",
                  "version": "%s",
                  "description": "%s"
                }
                """.formatted(name, version, description));
    }

    private void writeMarketplaceJson(Path dir, String topName, String pluginName, String pluginDesc) throws IOException {
        Files.writeString(dir.resolve("marketplace.json"), """
                {
                  "name": "%s",
                  "plugins": [
                    {
                      "name": "%s",
                      "description": "%s"
                    }
                  ]
                }
                """.formatted(topName, pluginName, pluginDesc));
    }

    @Test
    void passesOnValidManifests() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, null, null));
    }

    @Test
    void detectsPlaceholderInPluginJsonName() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "${plugin.name}", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ValidateRelease.validate(tempDir, null, null));
        assertTrue(ex.getMessage().contains("plugin.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("name"), ex.getMessage());
        assertTrue(ex.getMessage().contains("${plugin.name}"), ex.getMessage());
    }

    @Test
    void detectsMissingFieldInMarketplaceJson() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        Files.writeString(dir.resolve("marketplace.json"), """
                {
                  "name": "shipsmooth-dev",
                  "plugins": [
                    {
                      "name": "shipsmooth-dev"
                    }
                  ]
                }
                """);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ValidateRelease.validate(tempDir, null, null));
        assertTrue(ex.getMessage().contains("marketplace.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("plugins[0].description"), ex.getMessage());
    }

    @Test
    void skipsGeminiValidationWhenOutputDirIsNull() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, null, null));
    }

    @Test
    void skipsGeminiValidationWhenFileAbsent() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        Path geminiDir = tempDir.resolve("build-gemini");
        Files.createDirectories(geminiDir);
        // gemini-extension.json intentionally absent

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, geminiDir, null));
    }

    @Test
    void detectsPlaceholderInGeminiExtensionJson() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        Path geminiDir = tempDir.resolve("build-gemini");
        Files.createDirectories(geminiDir);
        Files.writeString(geminiDir.resolve("gemini-extension.json"), """
                {
                  "name": "${plugin.name}",
                  "version": "0.3.7",
                  "description": "Agent coding workflow"
                }
                """);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ValidateRelease.validate(tempDir, geminiDir, null));
        assertTrue(ex.getMessage().contains("gemini-extension.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("name"), ex.getMessage());
    }

    // --- Codex payload (plan-77) ---------------------------------------------

    /** Writes a valid codex marketplace root: .agents/plugins/marketplace.json + plugins/<name>/.codex-plugin/plugin.json. */
    private Path writeCodexPayload(String pluginName, String pluginJsonName) throws IOException {
        Path codexDir = tempDir.resolve("build-codex");
        Path marketplaceDir = codexDir.resolve(".agents/plugins");
        Files.createDirectories(marketplaceDir);
        Files.writeString(marketplaceDir.resolve("marketplace.json"), """
                {
                  "name": "bitkentech",
                  "interface": { "displayName": "Plugin marketplace" },
                  "plugins": [
                    {
                      "name": "%s",
                      "source": { "source": "local", "path": "./plugins/%s" },
                      "policy": { "installation": "AVAILABLE", "authentication": "ON_INSTALL" },
                      "category": "Productivity"
                    }
                  ]
                }
                """.formatted(pluginName, pluginName));
        Path manifestDir = codexDir.resolve("plugins").resolve(pluginName).resolve(".codex-plugin");
        Files.createDirectories(manifestDir);
        Files.writeString(manifestDir.resolve("plugin.json"), """
                {
                  "name": "%s",
                  "version": "0.3.19",
                  "description": "Agent coding workflow",
                  "skills": "./skills/"
                }
                """.formatted(pluginJsonName));
        return codexDir;
    }

    @Test
    void passesOnValidCodexPayload() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth", "0.3.19", "Agent coding workflow");
        writeMarketplaceJson(dir, "bitkentech", "shipsmooth", "Agent coding workflow");
        Path codexDir = writeCodexPayload("shipsmooth", "shipsmooth");

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, null, codexDir));
    }

    @Test
    void skipsCodexValidationWhenMarketplaceAbsent() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth", "0.3.19", "Agent coding workflow");
        writeMarketplaceJson(dir, "bitkentech", "shipsmooth", "Agent coding workflow");

        Path codexDir = tempDir.resolve("build-codex");
        Files.createDirectories(codexDir);
        // marketplace.json intentionally absent

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, null, codexDir));
    }

    @Test
    void detectsPlaceholderInCodexPluginJson() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth", "0.3.19", "Agent coding workflow");
        writeMarketplaceJson(dir, "bitkentech", "shipsmooth", "Agent coding workflow");
        Path codexDir = writeCodexPayload("shipsmooth", "${plugin.name}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ValidateRelease.validate(tempDir, null, codexDir));
        assertTrue(ex.getMessage().contains("plugin.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("name"), ex.getMessage());
    }
}
