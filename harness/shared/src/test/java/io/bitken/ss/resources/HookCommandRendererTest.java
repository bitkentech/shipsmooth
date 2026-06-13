package io.bitken.ss.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

// Hook-command + companion-file emission moved off Os (now a pure leaf type in
// :plugin-model) to HookCommandRenderer here (plan-79 v5). These cases were
// previously OsTest.{windows,posix}_hookCommand_* / posix_copyResource_*.
class HookCommandRendererTest {

    @Test
    void windows_writesBatAndReturnsCmdExe(@TempDir Path hooksDir) throws Exception {
        String cmd = new HookCommandRenderer().render(Os.WINDOWS, hooksDir, "shipsmooth-windows", "shipsmooth", "0.3.10");
        assertTrue(cmd.contains("cmd.exe"), "Windows hook must use cmd.exe");
        assertTrue(cmd.contains("install-runtime.bat"), "Windows hook must reference bat file");
        assertTrue(cmd.contains("MSYS_NO_PATHCONV=1"), "Must set MSYS_NO_PATHCONV");
        assertTrue(cmd.contains("shipsmooth-windows"), "Cache root must use repo name");

        Path bat = hooksDir.resolve("install-runtime.bat");
        assertTrue(Files.exists(bat), "install-runtime.bat must be written");
        String batContent = Files.readString(bat);
        assertTrue(batContent.contains("xcopy"), "bat must use xcopy");
        assertTrue(batContent.contains("LOCALAPPDATA"), "bat destination must use LOCALAPPDATA");
        assertTrue(batContent.contains("\\shipsmooth\\"), "bat destination must use plugin name");
    }

    @Test
    void posix_returnsNodeDefault_andWritesNothing(@TempDir Path hooksDir) throws Exception {
        // No plugin.hook.command set (dev / default) -> Node entry point, no script copied.
        String cmd = new HookCommandRenderer().render(Os.POSIX, hooksDir, "shipsmooth", "shipsmooth", "0.3.3");
        assertTrue(cmd.contains("session-start.js"), "default Posix hook must reference session-start.js");
        assertFalse(Files.list(hooksDir).findAny().isPresent(),
            "default (node) Posix hook must not write any files");
    }

    @Test
    void posix_shInstaller_copiesScriptNextToHooks(@TempDir Path hooksDir) throws Exception {
        // When the hook bootstraps via the sh installer (prod), the static script is
        // copied next to hooks.json — mirroring how Windows writes install-runtime.bat.
        String shHook = "sh \"${CLAUDE_PLUGIN_ROOT}/hooks/" + HookCommandRenderer.INSTALL_SCRIPT_NAME + "\" shipsmooth 0.3.3";
        System.setProperty("plugin.hook.command", shHook);
        try {
            String cmd = new HookCommandRenderer().render(Os.POSIX, hooksDir, "shipsmooth", "shipsmooth", "0.3.3");
            assertEquals(shHook, cmd, "Posix hook must return the sh invocation verbatim");

            Path script = hooksDir.resolve(HookCommandRenderer.INSTALL_SCRIPT_NAME);
            assertTrue(Files.exists(script), HookCommandRenderer.INSTALL_SCRIPT_NAME + " must be copied next to hooks.json");
            String body = Files.readString(script);
            assertTrue(body.startsWith("#!/bin/sh"), "installer must be a POSIX sh script");
            assertTrue(body.contains("curl"), "installer must download with curl");
            assertFalse(body.contains("session-start.js"), "installer must not reference the Node entry point");
        } finally {
            System.clearProperty("plugin.hook.command");
        }
    }

    @Test
    void copyResource_missingResource_throws(@TempDir Path hooksDir) {
        HookCommandRenderer renderer = new HookCommandRenderer();
        Path dest = hooksDir.resolve("nope.sh");
        IOException ex = assertThrows(IOException.class,
            () -> renderer.copyResource("does-not-exist-on-classpath.sh", dest));
        assertTrue(ex.getMessage().contains("not found on classpath"),
            "missing bundled resource must raise a clear IOException");
    }
}
