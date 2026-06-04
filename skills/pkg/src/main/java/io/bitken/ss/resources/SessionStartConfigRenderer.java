package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes session-start-config.json — the plugin version, name, and optional jlink dir
 * read by the session-start hook at runtime.
 */
class SessionStartConfigRenderer {

    private final ObjectMapper mapper;
    private final PluginModel model;
    private final Path outputDir;

    SessionStartConfigRenderer(ObjectMapper mapper, PluginModel model, Path outputDir) {
        this.mapper = mapper;
        this.model = model;
        this.outputDir = outputDir;
    }

    void write() throws IOException {
        Path distDir = outputDir.resolve("dist");
        Files.createDirectories(distDir);
        ObjectNode config = mapper.createObjectNode()
            .put("version", model.pluginVersion())
            .put("name", model.pluginName());
        if (model.hasJlinkDir()) {
            config.put("jlinkDir", model.jlinkDir());
        }
        Path outputFile = distDir.resolve("session-start-config.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), config);
        System.out.println("Written session-start-config.json to " + distDir.toAbsolutePath());
    }
}
