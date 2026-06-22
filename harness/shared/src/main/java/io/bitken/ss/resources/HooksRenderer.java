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

    /**
     * Full SessionStart render: copy the installer script AND write hooks.json.
     * Retained for hook-based hosts (Claude/Codex/Gemini/Windows) where Target
     * still wants both. OpenCode calls only {@link #writeInstallerScript()}
     * (plan-86 Task 8).
     */
    void write() throws IOException {
        String command = writeInstallerScript();
        writeHooksJson(command);
    }

    /**
     * Emits the OS-specific installer companion file (install-shipsmooth.sh /
     * install-runtime.bat) next to the hooks dir and returns the hook command
     * string. Runs for EVERY host — OpenCode needs the script even though it
     * writes no hooks.json. The script copy is a side effect of
     * HookCommandRenderer.render (the POSIX branch copies install-shipsmooth.sh
     * when the command references it).
     */
    String writeInstallerScript() throws IOException {
        Path hooksDir = outputDir.resolve("hooks");
        Files.createDirectories(hooksDir);
        return hookCommandRenderer.render(
            model.os(), hooksDir, model.repoName(), model.pluginName(), model.pluginVersion());
    }

    /**
     * Writes hooks/hooks.json wrapping {@code command} as a SessionStart command
     * hook. Only hook-based hosts call this; OpenCode skips it.
     */
    void writeHooksJson(String command) throws IOException {
        Path hooksDir = outputDir.resolve("hooks");
        Files.createDirectories(hooksDir);

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
