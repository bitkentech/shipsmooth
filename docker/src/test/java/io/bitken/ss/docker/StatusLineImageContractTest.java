package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Preamble integration test (plan-114). Pins the end-to-end contract of baking a
 * Claude Code status line into the {@code shipsmooth-claude} image:
 *
 * <ol>
 *   <li>The {@code Dockerfile} copies {@code scripts/statusline.sh} to a stable
 *       path, makes it executable, and adds the {@code statusLine} key to
 *       {@code /root/.claude/settings.json} by <em>merging</em> — the layer sits
 *       after {@code claude plugin install} (which creates that file) and never
 *       redirects {@code jq}'s output back onto its own input.</li>
 *   <li>The exact {@code jq} program the Dockerfile uses, run against a fixture
 *       matching the published image's {@code settings.json}, keeps
 *       {@code enabledPlugins} intact and adds {@code statusLine.command}.</li>
 * </ol>
 *
 * Red until Task 1 adds {@code docker/scripts/statusline.sh} and Task 2 wires the
 * Dockerfile layer.
 */
class StatusLineImageContractTest {

    /** The merge program baked into the Dockerfile. The Dockerfile must contain this verbatim. */
    private static final String JQ_STATUSLINE_PROGRAM =
            ".statusLine = {\"type\":\"command\",\"command\":\"~/.claude/scripts/statusline.sh\"}";

    /** Matches the published image's settings.json after `claude plugin install shipsmooth`. */
    private static final String PUBLISHED_SETTINGS_JSON =
            """
            {
              "extraKnownMarketplaces": {
                "bitkentech": { "source": { "source": "github", "repo": "bitkentech/claude-plugins" } }
              },
              "enabledPlugins": { "shipsmooth@bitkentech": true }
            }
            """;

    @Test
    void dockerfileCopiesTheScriptAndMergesSettingsAfterPluginInstall() throws IOException {
        List<String> lines = Files.readAllLines(dockerfile(), StandardCharsets.UTF_8);
        String body = String.join("\n", lines);

        int pluginInstallLine = firstLineContaining(lines, "claude plugin install");
        int copyScriptLine = firstLineContaining(lines, "COPY scripts/statusline.sh");
        int mergeLine = firstLineContaining(lines, ".statusLine");

        assertTrue(pluginInstallLine >= 0, "Dockerfile must run `claude plugin install`");
        assertTrue(
                copyScriptLine >= 0,
                "Dockerfile must `COPY scripts/statusline.sh` into the image");
        assertTrue(mergeLine >= 0, "Dockerfile must set the .statusLine settings key");

        assertTrue(
                body.contains("COPY scripts/statusline.sh /root/.claude/scripts/statusline.sh"),
                "script must land at the stable path /root/.claude/scripts/statusline.sh");
        assertTrue(
                body.contains("chmod +x /root/.claude/scripts/statusline.sh"),
                "the copied script must be made executable");

        assertTrue(
                mergeLine > pluginInstallLine,
                () -> "the .statusLine merge (line " + (mergeLine + 1) + ") must come AFTER "
                        + "`claude plugin install` (line " + (pluginInstallLine + 1) + "), which "
                        + "creates /root/.claude/settings.json");

        assertTrue(
                body.contains(JQ_STATUSLINE_PROGRAM),
                () -> "Dockerfile must apply the jq merge program verbatim:\n  " + JQ_STATUSLINE_PROGRAM);

        // Guard the classic `cmd file > file` truncation mistake: the merge must
        // write a temp file and `mv` it, never redirect straight onto settings.json.
        assertTrue(
                !body.contains("> /root/.claude/settings.json"),
                "jq merge must not redirect its output back onto /root/.claude/settings.json "
                        + "(truncates before jq reads it) — write a temp file and `mv`");
        assertTrue(
                body.contains("mv ") && body.contains("/root/.claude/settings.json"),
                "jq merge should `mv` a temp file onto /root/.claude/settings.json");
    }

    @Test
    void smokeShVerifiesTheBakedInStatusLine() throws IOException {
        String smoke = String.join("\n", Files.readAllLines(smokeSh(), StandardCharsets.UTF_8));

        assertTrue(
                smoke.contains(".statusLine.command"),
                "smoke.sh must assert .statusLine.command survived the settings.json merge");
        assertTrue(
                smoke.contains("test -x /root/.claude/scripts/statusline.sh"),
                "smoke.sh must assert the baked-in script is present and executable");
    }

    @Test
    void jqMergeKeepsEnabledPluginsAndAddsTheStatusLine() throws IOException, InterruptedException {
        assumeTrue(onPath("jq"), "jq not on PATH — skipping the live merge check");

        // Sanity: the program this test exercises is the one the Dockerfile ships.
        assertTrue(
                String.join("\n", Files.readAllLines(dockerfile(), StandardCharsets.UTF_8))
                        .contains(JQ_STATUSLINE_PROGRAM),
                "Dockerfile no longer contains the jq program this test verifies");

        String merged = runJq(JQ_STATUSLINE_PROGRAM, PUBLISHED_SETTINGS_JSON);

        assertTrue(
                runJq("-r", ".enabledPlugins[\"shipsmooth@bitkentech\"]", merged).trim().equals("true"),
                () -> "merge clobbered enabledPlugins; got:\n" + merged);
        assertTrue(
                runJq("-r", ".statusLine.command", merged).trim().equals("~/.claude/scripts/statusline.sh"),
                () -> "merge did not add statusLine.command; got:\n" + merged);
        assertTrue(
                runJq("-r", ".statusLine.type", merged).trim().equals("command"),
                () -> "statusLine.type should be \"command\"; got:\n" + merged);
    }

    // --- helpers ---------------------------------------------------------------

    private static Path dockerfile() {
        for (Path candidate : List.of(Path.of("Dockerfile"), Path.of("docker", "Dockerfile"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Dockerfile not found from " + Path.of("").toAbsolutePath());
    }

    private static Path smokeSh() {
        for (Path candidate : List.of(Path.of("smoke.sh"), Path.of("docker", "smoke.sh"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("smoke.sh not found from " + Path.of("").toAbsolutePath());
    }

    private static int firstLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean onPath(String cmd) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, cmd))) {
                return true;
            }
        }
        return false;
    }

    /** Run {@code jq <args...>} with {@code stdin} piped in; return stdout (fails on non-zero exit). */
    private static String runJq(String... argsThenStdin) throws IOException, InterruptedException {
        String stdin = argsThenStdin[argsThenStdin.length - 1];
        List<String> argv = new java.util.ArrayList<>();
        argv.add("jq");
        for (int i = 0; i < argsThenStdin.length - 1; i++) {
            argv.add(argsThenStdin[i]);
        }
        Process p = new ProcessBuilder(argv).redirectErrorStream(false).start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("jq " + argv + " exited " + code + ": " + err);
        }
        return out;
    }
}
