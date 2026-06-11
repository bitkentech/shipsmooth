package io.bitken.ss.resources;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan-76 preamble: end-to-end proof that a prod Posix render produces a
 * Node-free bootstrap. The {@code SessionStart} hook must invoke
 * {@code install-shipsmooth.sh} via {@code sh} (not {@code node session-start.js}),
 * and that script must be emitted alongside hooks.json as a self-contained POSIX
 * installer (curl + unzip). Dev renders keep the {@code node} path as a backup.
 *
 * <p>These tests fail until Tasks 1-3 land (no generator, no sh hook wiring).
 */
class PosixBootstrapIntegrationTest {

    @TempDir
    Path tempDir;

    private static final List<String> PROPS = List.of(
        "build.outputDir", "build.env", "build.platform", "build.os", "plugin.base.name",
        "plugin.skill.start.basename", "plugin.version", "plugin.description",
        "skill.frontmatter", "shipsmooth.jlink.dir", "experimental.enabled", "plugin.hook.command",
        "plugin.repo.name"
    );

    @AfterEach
    void clearProps() {
        PROPS.forEach(System::clearProperty);
    }

    /**
     * The headline feature: a prod claude render bootstraps without Node. hooks.json
     * runs {@code sh ".../hooks/install-shipsmooth.sh"} and the script is written next
     * to it as a real {@code #!/bin/sh} installer that uses curl + unzip — and the
     * Node entry point is gone from the hook.
     */
    @Test
    void prodPosixRender_bootstrapsViaShScript_notNode() throws Exception {
        setProdPosixProps();
        Target.main(new String[]{});

        Path hooks = tempDir.resolve("hooks/hooks.json");
        assertTrue(Files.exists(hooks), "hooks.json should be written");
        String hooksJson = Files.readString(hooks);

        assertTrue(hooksJson.contains("install-shipsmooth.sh"),
            "prod Posix hook must invoke install-shipsmooth.sh");
        assertTrue(hooksJson.contains("sh "),
            "prod Posix hook must run the script with sh");
        assertFalse(hooksJson.contains("session-start.js"),
            "prod Posix hook must NOT reference the Node entry point");
        assertTrue(hooksJson.contains("CLAUDE_PLUGIN_ROOT"),
            "claude hook must locate the script via CLAUDE_PLUGIN_ROOT");

        Path script = tempDir.resolve("hooks/install-shipsmooth.sh");
        assertTrue(Files.exists(script), "install-shipsmooth.sh must be emitted next to hooks.json");
        String sh = Files.readString(script);
        assertTrue(sh.startsWith("#!/bin/sh"),
            "installer must be a strict POSIX sh script");
        assertTrue(sh.contains("curl"), "installer must download with curl (no Node, no wget)");
        assertFalse(sh.contains("wget"), "installer must not use wget (absent on stock macOS)");
        assertTrue(sh.contains("unzip"), "installer must extract with unzip");
        assertTrue(sh.contains("0.2.0"), "installer must bake the runtime version");
    }

    /**
     * The backup path: dev renders still ship the Node bootstrap, because the TS
     * installer's local-jlink branch has no sh equivalent yet. Locks the split so a
     * later change can't silently flip dev onto the (incomplete) sh path.
     */
    @Test
    void devPosixRender_keepsNodeBootstrap() throws Exception {
        setDevPosixProps();
        Target.main(new String[]{});

        String hooksJson = Files.readString(tempDir.resolve("hooks/hooks.json"));
        assertTrue(hooksJson.contains("session-start.js"),
            "dev Posix hook must keep the Node entry point (backup bootstrap)");
        assertFalse(hooksJson.contains("install-shipsmooth.sh"),
            "dev Posix hook must NOT use the sh installer this cycle");
    }

    private void setProdPosixProps() {
        System.setProperty("build.outputDir", tempDir.toString());
        System.setProperty("build.env", "prod");
        System.setProperty("build.platform", "claude");
        System.setProperty("build.os", "posix");
        System.setProperty("plugin.base.name", "shipsmooth");
        System.setProperty("plugin.skill.start.basename", "start");
        System.setProperty("plugin.version", "0.2.0");
        System.setProperty("plugin.description", "Agent coding workflow");
        System.setProperty("skill.frontmatter", "");
        System.setProperty("shipsmooth.jlink.dir", "/dev/null");
        System.setProperty("experimental.enabled", "false");
        // Mirrors build.gradle.kts claudeProdSpec after Task 3.
        System.setProperty("plugin.hook.command",
            "sh \"${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth.sh\"");
    }

    private void setDevPosixProps() {
        System.setProperty("build.outputDir", tempDir.toString());
        System.setProperty("build.env", "dev");
        System.setProperty("build.platform", "claude");
        System.setProperty("build.os", "posix");
        System.setProperty("plugin.base.name", "shipsmooth");
        System.setProperty("plugin.skill.start.basename", "start");
        System.setProperty("plugin.version", "0.2.0");
        System.setProperty("plugin.description", "Agent coding workflow (dev build)");
        System.setProperty("skill.frontmatter", "");
        System.setProperty("shipsmooth.jlink.dir", "/some/jlink/path");
        System.setProperty("experimental.enabled", "true");
        // Dev keeps the Node command (build.gradle.kts claudeDevSpec, unchanged).
        System.setProperty("plugin.hook.command",
            "node \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"");
    }
}
