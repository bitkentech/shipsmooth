package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the SessionStart hooks.json, plus the Windows install-runtime.bat that the
 * hook command invokes when building for the windows platform.
 */
class HooksRenderer {

    private final ObjectMapper mapper;
    private final PluginModel model;
    private final Path outputDir;

    HooksRenderer(ObjectMapper mapper, PluginModel model, Path outputDir) {
        this.mapper = mapper;
        this.model = model;
        this.outputDir = outputDir;
    }

    void write() throws IOException {
        Path hooksDir = outputDir.resolve("hooks");
        Files.createDirectories(hooksDir);
        ObjectNode hook = mapper.createObjectNode()
            .put("type", "command")
            .put("command", hookCommand(hooksDir));

        ArrayNode innerHooks = mapper.createArrayNode().add(hook);
        ObjectNode hookGroup = mapper.createObjectNode().set("hooks", innerHooks);
        ArrayNode sessionStart = mapper.createArrayNode().add(hookGroup);

        ObjectNode root = mapper.createObjectNode();
        root.putObject("hooks").set("SessionStart", sessionStart);

        Path outputFile = hooksDir.resolve("hooks.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        System.out.println("Written hooks.json to " + hooksDir.toAbsolutePath());
    }

    private String hookCommand(Path hooksDir) throws IOException {
        if (!model.isWindows()) {
            return System.getProperty("plugin.hook.command", "node \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"");
        }
        writeInstallRuntimeBat(hooksDir.resolve("install-runtime.bat"));
        // MSYS_NO_PATHCONV=1 prevents Git Bash's MSYS2 layer from translating /C to C:
        return "MSYS_NO_PATHCONV=1 cmd.exe /C \"" + model.windowsCacheRoot() + "\\hooks\\install-runtime.bat\"";
    }

    private void writeInstallRuntimeBat(Path outputFile) throws IOException {
        String dest = model.windowsRuntimeDest();
        String src  = model.windowsCacheRoot() + "\\runtime";
        String bat = "@echo off\r\n" +
                     "if exist \"" + src + "\" (\r\n" +
                     "    mkdir \"" + dest + "\" 2>nul\r\n" +
                     "    xcopy /E /Y /I \"" + src + "\" \"" + dest + "\"\r\n" +
                     ")\r\n";
        Files.writeString(outputFile, bat);
    }
}
