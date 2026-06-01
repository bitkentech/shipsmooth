package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the SessionStart hooks.json. OS-specific hook files (e.g. install-runtime.bat)
 * are written by Os as a side-effect of hookCommand().
 */
class HooksRenderer {

    private final ObjectMapper mapper;
    private final PluginModel model;
    private final Os os;
    private final Path outputDir;

    HooksRenderer(ObjectMapper mapper, PluginModel model, Os os, Path outputDir) {
        this.mapper = mapper;
        this.model = model;
        this.os = os;
        this.outputDir = outputDir;
    }

    void write() throws IOException {
        Path hooksDir = outputDir.resolve("hooks");
        Files.createDirectories(hooksDir);

        String command = os.hookCommand(hooksDir, model.repoName(), model.pluginName(), model.pluginVersion());

        ObjectNode hook = mapper.createObjectNode()
            .put("type", "command")
            .put("command", command);

        ArrayNode innerHooks = mapper.createArrayNode().add(hook);
        ObjectNode hookGroup = mapper.createObjectNode().set("hooks", innerHooks);
        ArrayNode sessionStart = mapper.createArrayNode().add(hookGroup);

        ObjectNode root = mapper.createObjectNode();
        root.putObject("hooks").set("SessionStart", sessionStart);

        Path outputFile = hooksDir.resolve("hooks.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        System.out.println("Written hooks.json to " + hooksDir.toAbsolutePath());
    }
}
