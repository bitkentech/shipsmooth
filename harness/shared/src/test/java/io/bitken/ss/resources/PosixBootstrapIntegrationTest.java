package io.bitken.ss.resources;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        // name + version are passed as args, not baked into the script body.
        assertTrue(hooksJson.contains("shipsmooth 0.2.0"),
            "hook must pass the cache-subdir name and version as args");

        Path script = tempDir.resolve("hooks/install-shipsmooth.sh");
        assertTrue(Files.exists(script), "install-shipsmooth.sh must be emitted next to hooks.json");
        String sh = Files.readString(script);
        assertTrue(sh.startsWith("#!/bin/sh"),
            "installer must be a strict POSIX sh script");
        assertTrue(sh.contains("curl"), "installer must download with curl (no Node, no wget)");
        assertFalse(sh.contains("wget"), "installer must not use wget (absent on stock macOS)");
        assertTrue(sh.contains("unzip"), "installer must extract with unzip");
        // The script is static (args-driven), so the version is NOT baked into its body.
        assertFalse(sh.contains("0.2.0"), "static installer must not bake the version");
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

    /**
     * Gemini prod locates the script via {@code ${extensionPath}} (not CLAUDE_PLUGIN_ROOT)
     * — the gemini-specific plugin-root placeholder — and still copies the script.
     */
    @Test
    void prodGeminiRender_bootstrapsViaShScript_withExtensionPath() throws Exception {
        setProdPosixProps();
        System.setProperty("build.platform", "gemini");
        System.setProperty("plugin.hook.command",
            "sh \"${extensionPath}/hooks/install-shipsmooth.sh\" shipsmooth 0.2.0");
        Target.main(new String[]{});

        String hooksJson = Files.readString(tempDir.resolve("hooks/hooks.json"));
        assertTrue(hooksJson.contains("install-shipsmooth.sh"),
            "gemini prod hook must invoke the sh installer");
        assertTrue(hooksJson.contains("extensionPath"),
            "gemini hook must locate the script via extensionPath");
        assertFalse(hooksJson.contains("CLAUDE_PLUGIN_ROOT"),
            "gemini hook must not use the claude placeholder");
        assertFalse(hooksJson.contains("session-start.js"),
            "gemini prod hook must not reference the Node entry point");
        assertTrue(Files.exists(tempDir.resolve("hooks/install-shipsmooth.sh")),
            "gemini prod render must copy the script");
    }

    /** Gemini dev, like claude dev, keeps the Node bootstrap this cycle. */
    @Test
    void devGeminiRender_keepsNodeBootstrap() throws Exception {
        setDevPosixProps();
        System.setProperty("build.platform", "gemini");
        System.setProperty("plugin.hook.command",
            "node \"${extensionPath}/dist/session-start.js\"");
        Target.main(new String[]{});

        String hooksJson = Files.readString(tempDir.resolve("hooks/hooks.json"));
        assertTrue(hooksJson.contains("session-start.js"),
            "gemini dev hook must keep the Node entry point");
        assertFalse(hooksJson.contains("install-shipsmooth.sh"),
            "gemini dev hook must NOT use the sh installer this cycle");
    }

    /**
     * End-to-end: render prod, then actually RUN the copied script against a synthetic
     * "release zip" served over file://. Proves the real download -> unzip -> chmod -> mv
     * flow and, critically, that unzip restores the stored +x bit (the OpenJ9 jspawnhelper
     * hazard). Hermetic — builds its own zip, no network and no gitignored release artifact.
     * Skips where the POSIX toolchain (sh/zip/unzip) is unavailable (e.g. Windows CI).
     *
     * <p>plan-115: runs for BOTH products. shipsmooth-beta ships its own near-copy
     * installer (launcher bin/shipsmooth-beta) so the Java one stays untouched; driving
     * both through the same install, exec-bit and idempotency cases is what keeps the
     * two copies from drifting apart.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "shipsmooth,      0.2.0,      install-shipsmooth.sh",
        "shipsmooth-beta, 0.1.0-beta, install-shipsmooth-beta.sh",
    })
    void copiedScript_installsRuntime_preservingExecBit(String product, String version, String scriptName,
                                                        @TempDir Path work) throws Exception {
        assumeTrue(toolsPresent("sh", "zip", "unzip"),
            "requires sh + zip + unzip on PATH");

        setProdPosixProps();
        System.setProperty("plugin.base.name", product);
        System.setProperty("plugin.version", version);
        System.setProperty("plugin.hook.command",
            "sh \"${CLAUDE_PLUGIN_ROOT}/hooks/" + scriptName + "\" " + product + " " + version);
        Target.main(new String[]{});
        Path script = tempDir.resolve("hooks/" + scriptName);
        assertTrue(Files.exists(script), "render must have copied " + scriptName);

        // Build a fake release: bin/<product> (executable, +x stored in the zip).
        Path stage = Files.createDirectories(work.resolve("stage/bin"));
        Path fakeBin = stage.resolve(product);
        Files.writeString(fakeBin, "#!/bin/sh\necho fake-runtime\n");
        Files.setPosixFilePermissions(fakeBin, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        Path releases = Files.createDirectories(work.resolve("releases"));
        // Zip name must match what the script builds: <name>-<version>-<platform>.zip
        String platform = detectLinuxOrDarwinPlatform();
        Path zip = releases.resolve(product + "-" + version + "-" + platform + ".zip");
        run(work.resolve("stage"), "zip", "-q", "-r", "-X", zip.toString(), "bin");

        Path cache = work.resolve("cache");
        int code = runInstaller(script, cache, releases, product, version);
        assertEquals(0, code, "installer must exit 0");

        Path installedBin = cache.resolve(product + "/" + version + "/bin/" + product);
        assertTrue(Files.exists(installedBin), "runtime launcher must be installed");
        assertTrue(Files.isExecutable(installedBin),
            "unzip must restore the stored +x bit on the launcher");

        // Idempotent: a second run sees the executable launcher and no-ops (exit 0)
        // without downloading — an empty release dir would fail any fetch.
        assertEquals(0, runInstaller(script, cache, Files.createDirectories(work.resolve("none")), product, version),
            "second install must no-op");
    }

    /** plan-115: a beta payload carries only its own installer, never the Java one. */
    @Test
    void betaRender_shipsOnlyItsOwnInstaller() throws Exception {
        setProdPosixProps();
        System.setProperty("plugin.base.name", "shipsmooth-beta");
        System.setProperty("plugin.version", "0.1.0-beta");
        System.setProperty("plugin.hook.command",
            "sh \"${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth-beta.sh\" shipsmooth-beta 0.1.0-beta");
        Target.main(new String[]{});

        assertTrue(Files.exists(tempDir.resolve("hooks/install-shipsmooth-beta.sh")));
        assertFalse(Files.exists(tempDir.resolve("hooks/install-shipsmooth.sh")),
            "beta render must not ship the Java installer");
    }

    private int runInstaller(Path script, Path cache, Path releases, String name, String version)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "sh", script.toString(), name, version).inheritIO();
        pb.environment().put("XDG_CACHE_HOME", cache.toString());
        pb.environment().put("SS_URL_BASE", releases.toUri().toString());
        return pb.start().waitFor();
    }

    private void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        int code = new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor();
        assertEquals(0, code, "command failed: " + String.join(" ", cmd));
    }

    private static String detectLinuxOrDarwinPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String o = os.contains("mac") ? "darwin" : "linux";
        String a = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "x64";
        return o + "-" + a;
    }

    private static boolean toolsPresent(String... tools) {
        for (String t : tools) {
            try {
                if (new ProcessBuilder("sh", "-c", "command -v " + t)
                        .redirectErrorStream(true).start().waitFor() != 0) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
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
        // Mirrors build.gradle.kts claudeProdSpec after Task 3 (name + version as args).
        System.setProperty("plugin.hook.command",
            "sh \"${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth 0.2.0");
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
