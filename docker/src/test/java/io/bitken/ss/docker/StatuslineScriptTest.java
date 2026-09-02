package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@code docker/scripts/statusline.sh} — the script baked into the
 * image and wired as Claude Code's {@code statusLine} command (plan-114, Task 1).
 *
 * <p>Claude Code pipes a JSON blob to the script on stdin and prints its stdout.
 * This runs the real script against a canned blob and pins the output shape. The
 * script is ported byte-for-byte from the host original, so this asserts the
 * ported contract — notably it does <em>not</em> object to the {@code pro: …%%}
 * double {@code %}, which is deliberate.
 */
class StatuslineScriptTest {

    private static final String FIXTURE =
            """
            {
              "model": { "display_name": "Sonnet 5" },
              "context_window": {
                "used_percentage": 42,
                "current_usage": { "input": 1200, "output": 800 }
              },
              "rate_limits": {
                "five_hour": { "used_percentage": 12.5, "resets_at": 4102444800 },
                "seven_day": { "used_percentage": 30, "resets_at": 4102444800 }
              }
            }
            """;

    @Test
    void printsModelAndUsageFields() throws IOException, InterruptedException {
        assumeTrue(onPath("jq") && onPath("bc"), "jq/bc not on PATH — skipping statusline.sh run");

        Result r = run(FIXTURE);

        assertEquals(0, r.exitCode, () -> "statusline.sh exited " + r.exitCode + "; stderr:\n" + r.stderr);
        assertTrue(r.stdout.contains("Sonnet 5"), () -> "model name missing:\n" + r.stdout);
        assertTrue(r.stdout.contains("ctx:"), () -> "ctx field missing:\n" + r.stdout);
        assertTrue(r.stdout.contains("pro:"), () -> "pro field missing:\n" + r.stdout);
        assertTrue(r.stdout.contains("wk:"), () -> "wk field missing:\n" + r.stdout);
    }

    @Test
    void toleratesMissingFieldsWithoutCrashing() throws IOException, InterruptedException {
        assumeTrue(onPath("jq") && onPath("bc"), "jq/bc not on PATH — skipping statusline.sh run");

        Result r = run("{ \"model\": { \"display_name\": \"Opus\" } }");

        assertEquals(0, r.exitCode, () -> "statusline.sh exited " + r.exitCode + "; stderr:\n" + r.stderr);
        assertTrue(r.stdout.contains("Opus"), () -> "model name missing:\n" + r.stdout);
    }

    // --- helpers ---------------------------------------------------------------

    private record Result(int exitCode, String stdout, String stderr) {}

    private static Path script() {
        for (Path candidate :
                java.util.List.of(
                        Path.of("scripts", "statusline.sh"), Path.of("docker", "scripts", "statusline.sh"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("statusline.sh not found from " + Path.of("").toAbsolutePath());
    }

    private static Result run(String stdin) throws IOException, InterruptedException {
        Process p =
                new ProcessBuilder("bash", script().toAbsolutePath().toString())
                        .redirectErrorStream(false)
                        .start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out, err);
    }

    private static boolean onPath(String cmd) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, cmd))) {
                return true;
            }
        }
        return false;
    }
}
