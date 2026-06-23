package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the split introduced in plan-86 Task 8: writeInstallerScript()
 * (always) vs writeHooksJson() (hook-based hosts only). Exercises each method in
 * isolation so the two responsibilities — ship the installer, write the manifest —
 * are independently verified, complementing the end-to-end TargetIntegrationTest.
 */
class HooksRendererTest {

    @TempDir
    Path tempDir;

    /** Posix prod model whose hook command references the sh installer (so the
     *  POSIX branch of HookCommandRenderer copies install-shipsmooth.sh). */
    private PluginModel posixProdModel() {
        return new PluginModel(
            "shipsmooth", "0.2.0", "desc", "start",
            Os.POSIX.cliBinPath("shipsmooth", "0.2.0",
                Platform.OPENCODE.cacheSubdir("shipsmooth", Env.PROD)),
            "", Platform.OPENCODE.skillFragmentDir(), Platform.OPENCODE.id(),
            Os.POSIX, Env.PROD, "", null, false
        );
    }

    private HooksRenderer renderer(PluginModel model) {
        return new HooksRenderer(new ObjectMapper(), model, tempDir);
    }

    @Test
    void writeInstallerScript_copiesScript_andReturnsCommand() throws Exception {
        System.setProperty("plugin.hook.command",
            "sh \"${PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth 0.2.0");
        try {
            String command = renderer(posixProdModel()).writeInstallerScript();

            assertTrue(command.contains("install-shipsmooth.sh"),
                "command should reference the installer");
            assertTrue(Files.exists(tempDir.resolve("hooks/install-shipsmooth.sh")),
                "the installer script must be copied next to the hooks dir");
        } finally {
            System.clearProperty("plugin.hook.command");
        }
    }

    @Test
    void writeInstallerScript_doesNotWriteHooksJson() throws Exception {
        System.setProperty("plugin.hook.command",
            "sh \"${PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth 0.2.0");
        try {
            renderer(posixProdModel()).writeInstallerScript();

            assertFalse(Files.exists(tempDir.resolve("hooks/hooks.json")),
                "writeInstallerScript must NOT write hooks.json — that is writeHooksJson's job");
        } finally {
            System.clearProperty("plugin.hook.command");
        }
    }

    @Test
    void writeHooksJson_wrapsCommandAsSessionStartHook() throws Exception {
        renderer(posixProdModel()).writeHooksJson("MY_HOOK_COMMAND");

        Path json = tempDir.resolve("hooks/hooks.json");
        assertTrue(Files.exists(json), "hooks.json should be written");

        String content = Files.readString(json);
        assertTrue(content.contains("SessionStart"), "json must declare a SessionStart hook");
        assertTrue(content.contains("\"command\""), "json must carry a command entry");
        assertTrue(content.contains("MY_HOOK_COMMAND"), "json must embed the passed command");
        assertTrue(content.contains("\"type\" : \"command\""), "hook type must be command");
    }
}
