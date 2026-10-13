package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan-115 preamble: end-to-end proof that the {@code shipsmooth-beta} product installs
 * and runs from its own artifacts, and coexists with {@code shipsmooth} in one cache.
 *
 * <p>Consumes REAL build outputs rather than re-deriving them, so it exercises exactly
 * what a user receives: the rendered Claude beta payload ({@code beta.render.dir}), the
 * beta release zip built from the Rust CLI ({@code beta.dist.dir}), and the rendered
 * Claude prod payload ({@code java.render.dir}). The {@code betaE2eTest} Gradle task
 * passes those paths; the test fails (never skips) when an artifact is missing, because
 * a skipped end-to-end proof proves nothing.
 *
 * <p>Tagged {@code beta-e2e} and excluded from the default {@code test} task: the beta zip
 * comes from the standalone {@code exp/rust} build, which needs a Rust toolchain that the
 * root build must not require.
 */
@Tag("beta-e2e")
class BetaReleaseIntegrationTest {

    private static final Pattern SS_LINE = Pattern.compile("(?m)^SS=\"([^\"]+)\"$");
    private static final Pattern BETA_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+-beta(\\.\\d+)?");

    @TempDir
    Path work;

    /** IT-1: the rendered beta hook installs the Rust binary, and the skill's SS= path runs it. */
    @Test
    void betaHook_installsRustBinary_thatSkillPathRuns() throws Exception {
        Path betaRender = requiredDir("beta.render.dir");
        Path betaDist = requiredDir("beta.dist.dir");
        Map<String, String> env = sandboxEnv(betaRender, betaDist);

        String hook = sessionStartCommand(betaRender);
        assertTrue(hook.contains("/hooks/install-shipsmooth-beta.sh\" shipsmooth-beta "),
            "beta hook must invoke its own installer with NAME=shipsmooth-beta: " + hook);
        assertTrue(Files.exists(betaRender.resolve("hooks/install-shipsmooth-beta.sh")),
            "beta installer must be rendered next to hooks.json");

        Exec install = sh(env, hook);
        assertEquals(0, install.code(), "beta install must succeed:\n" + install.output());

        String ssPath = expand(env, ssExpression(betaRender));
        assertTrue(ssPath.startsWith(work.resolve("cache/shipsmooth-beta/") + ""),
            "SS= must resolve inside the beta cache tree: " + ssPath);
        assertTrue(ssPath.endsWith("/bin/shipsmooth-beta"), "SS= must name the beta launcher: " + ssPath);

        String version = versionToken(ssPath);
        assertTrue(BETA_VERSION.matcher(version).matches(),
            "beta version must be a -beta prerelease (05 §3): " + version);
        Exec run = exec(env, ssPath, "--version");
        assertEquals(0, run.code(), "installed beta binary must run:\n" + run.output());
        assertEquals(version, run.output().strip(), "binary --version must equal the rendered version");
    }

    /**
     * IT-2: both products install into one cache with no interference — separate trees,
     * each SS= path runs its own product, and re-running either hook does not download.
     */
    @Test
    void bothProducts_coexistInOneCache_withoutRedownloadOrShadowing() throws Exception {
        Path betaRender = requiredDir("beta.render.dir");
        Path betaDist = requiredDir("beta.dist.dir");
        Path javaRender = requiredDir("java.render.dir");

        String javaHook = sessionStartCommand(javaRender);
        String javaSs = ssExpression(javaRender);
        Path javaDist = fakeJavaRelease(versionToken(javaSs));

        Map<String, String> betaEnv = sandboxEnv(betaRender, betaDist);
        Map<String, String> javaEnv = sandboxEnv(javaRender, javaDist);

        assertEquals(0, sh(javaEnv, javaHook).code(), "java install must succeed");
        Exec betaInstall = sh(betaEnv, sessionStartCommand(betaRender));
        assertEquals(0, betaInstall.code(), "beta install must succeed:\n" + betaInstall.output());

        assertEquals("fake-java-runtime", exec(javaEnv, expand(javaEnv, javaSs)).output().strip(),
            "installing the beta must not shadow the java launcher");
        String betaSs = expand(betaEnv, ssExpression(betaRender));
        assertEquals(versionToken(betaSs), exec(betaEnv, betaSs, "--version").output().strip(),
            "the beta SS= path must run the beta binary");

        // No re-download: point both hooks at an empty release dir; an idempotent
        // installer exits 0 without fetching anything.
        Path empty = Files.createDirectories(work.resolve("no-releases"));
        assertEquals(0, sh(sandboxEnv(javaRender, empty), javaHook).code(), "java re-run must no-op");
        assertEquals(0, sh(sandboxEnv(betaRender, empty), sessionStartCommand(betaRender)).code(),
            "beta re-run must no-op");
    }

    // ---- helpers -------------------------------------------------------------------

    private Path requiredDir(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, "system property " + property + " must be set (run via the betaE2eTest task)");
        Path dir = Path.of(value);
        assertTrue(Files.isDirectory(dir), property + " does not exist: " + dir);
        return dir;
    }

    /** Hermetic env: fake HOME/XDG cache, plugin root set, downloads served from {@code releases}. */
    private Map<String, String> sandboxEnv(Path pluginRoot, Path releases) throws IOException {
        return Map.of(
            "HOME", Files.createDirectories(work.resolve("home")).toString(),
            "XDG_CACHE_HOME", work.resolve("cache").toString(),
            "CLAUDE_PLUGIN_ROOT", pluginRoot.toString(),
            "SS_URL_BASE", releases.toUri().toString(),
            "PATH", System.getenv("PATH"));
    }

    private static String sessionStartCommand(Path render) throws IOException {
        JsonNode root = new ObjectMapper().readTree(render.resolve("hooks/hooks.json").toFile());
        return root.at("/hooks/SessionStart/0/hooks/0/command").asText();
    }

    private static String ssExpression(Path render) throws IOException {
        Matcher m = SS_LINE.matcher(Files.readString(render.resolve("skills/start/SKILL.md")));
        assertTrue(m.find(), "rendered start SKILL.md must carry an SS=\"...\" line");
        return m.group(1);
    }

    /** The {@code <version>} segment of {@code .../<cache>/<version>/bin/<launcher>}. */
    private static String versionToken(String cliBinPath) {
        Path p = Path.of(cliBinPath);
        return p.getParent().getParent().getFileName().toString();
    }

    private String expand(Map<String, String> env, String shellExpr) throws Exception {
        return sh(env, "printf '%s' \"" + shellExpr + "\"").output();
    }

    /** A Java-product release zip whose launcher prints a marker (the real jlink zip is not needed). */
    private Path fakeJavaRelease(String version) throws Exception {
        Path bin = Files.createDirectories(work.resolve("java-stage/bin"));
        Path launcher = bin.resolve("shipsmooth");
        Files.writeString(launcher, "#!/bin/sh\necho fake-java-runtime\n");
        Files.setPosixFilePermissions(launcher, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        Path releases = Files.createDirectories(work.resolve("java-releases"));
        Path zip = releases.resolve("shipsmooth-" + version + "-" + hostPlatform() + ".zip");
        Exec z = exec(Map.of("PATH", System.getenv("PATH")), work.resolve("java-stage"),
            "zip", "-q", "-r", "-X", zip.toString(), "bin");
        assertEquals(0, z.code(), "zip failed:\n" + z.output());
        return releases;
    }

    private static String hostPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return (os.contains("mac") ? "darwin" : "linux") + "-"
            + ((arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "x64");
    }

    private record Exec(int code, String output) {}

    private Exec sh(Map<String, String> env, String script) throws Exception {
        return exec(env, "sh", "-c", script);
    }

    private Exec exec(Map<String, String> env, String... cmd) throws Exception {
        return exec(env, work, cmd);
    }

    private static Exec exec(Map<String, String> env, Path cwd, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Exec(p.waitFor(), out);
    }
}
