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

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, null));
    }

    @Test
    void detectsPlaceholderInPluginJsonName() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "${plugin.name}", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ValidateRelease.validate(tempDir, null));
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
                () -> ValidateRelease.validate(tempDir, null));
        assertTrue(ex.getMessage().contains("marketplace.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("plugins[0].description"), ex.getMessage());
    }

    @Test
    void skipsGeminiValidationWhenOutputDirIsNull() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, null));
    }

    @Test
    void skipsGeminiValidationWhenFileAbsent() throws IOException {
        Path dir = claudePlugin();
        writePluginJson(dir, "shipsmooth-dev", "0.3.7", "Agent coding workflow");
        writeMarketplaceJson(dir, "shipsmooth-dev", "shipsmooth-dev", "Agent coding workflow");

        Path geminiDir = tempDir.resolve("build-gemini");
        Files.createDirectories(geminiDir);
        // gemini-extension.json intentionally absent

        assertDoesNotThrow(() -> ValidateRelease.validate(tempDir, geminiDir));
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
                () -> ValidateRelease.validate(tempDir, geminiDir));
        assertTrue(ex.getMessage().contains("gemini-extension.json"), ex.getMessage());
        assertTrue(ex.getMessage().contains("name"), ex.getMessage());
    }
}
