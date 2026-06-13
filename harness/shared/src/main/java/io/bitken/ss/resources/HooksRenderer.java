package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the SessionStart hooks.json. The OS-specific hook command and its
 * companion file (install-shipsmooth.sh / install-runtime.bat) are produced by
 * HookCommandRenderer (plan-79 v5 — moved off Os to keep Os a pure type).
 */
class HooksRenderer {

    private final ObjectMapper mapper;
    private final PluginModel model;
    private final Path outputDir;
    private final HookCommandRenderer hookCommandRenderer;

    HooksRenderer(ObjectMapper mapper, PluginModel model, Path outputDir) {
        this.mapper = mapper;
        this.model = model;
        this.outputDir = outputDir;
        this.hookCommandRenderer = new HookCommandRenderer();
    }

    void write() throws IOException {
        Path hooksDir = outputDir.resolve("hooks");
        Files.createDirectories(hooksDir);

        String command = hookCommandRenderer.render(
            model.os(), hooksDir, model.repoName(), model.pluginName(), model.pluginVersion());

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
