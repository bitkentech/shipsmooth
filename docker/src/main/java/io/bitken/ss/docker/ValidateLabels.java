package io.bitken.ss.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bitken.ss.docker.ResolveVersions.Versions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that an image's immutable OCI labels agree with the mutable channels that
 * repeat the same information — the Docker Hub Overview (default) or, in {@code --local}
 * mode, the versions actually present in the image. Exits non-zero on any mismatch.
 *
 * <p>Runnable on any tag at any time, not just right after a build. This is what makes
 * the Overview's drift risk tolerable rather than permanent.
 */
public final class ValidateLabels {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SEMVER = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");
    private static final String CLAUDE_LABEL = "io.bitken.ss.claude-code.version";
    private static final String SHIPSMOOTH_LABEL = "io.bitken.ss.shipsmooth.version";

    private ValidateLabels() {
    }

    // --- parsing (unit-tested) ---------------------------------------------------

    /** Component versions from {@code docker inspect <image>} output (a JSON array). */
    static Versions labelsFromInspect(String inspectJson) {
        try {
            JsonNode labels = MAPPER.readTree(inspectJson).path(0).path("Config").path("Labels");
            String claude = labels.path(CLAUDE_LABEL).asText("");
            String shipsmooth = labels.path(SHIPSMOOTH_LABEL).asText("");
            if (claude.isBlank() || shipsmooth.isBlank()) {
                throw new IllegalStateException("image is missing " + CLAUDE_LABEL + " / " + SHIPSMOOTH_LABEL);
            }
            return new Versions(claude, shipsmooth);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse `docker inspect` output", e);
        }
    }

    /** Component versions from the Overview markdown table. */
    static Versions versionsFromOverview(String markdown) {
        return new Versions(
                versionFromLineContaining(markdown, "claude-code"),
                versionFromLineContaining(markdown, "shipsmooth plugin"));
    }

    private static String versionFromLineContaining(String markdown, String needle) {
        for (String line : markdown.split("\n")) {
            if (line.toLowerCase().contains(needle.toLowerCase())) {
                Matcher m = SEMVER.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new IllegalStateException("Overview has no version row for '" + needle + "'");
    }

    /** The version token from {@code claude --version} output. */
    static String parseClaudeVersion(String cliOutput) {
        Matcher m = SEMVER.matcher(cliOutput);
        if (!m.find()) {
            throw new IllegalStateException("no version in `claude --version` output: " + cliOutput);
        }
        return m.group(1);
    }

    /** Strips any {@code :tag} from an image reference. */
    static String repoOf(String imageRef) {
        int slash = imageRef.lastIndexOf('/');
        int colon = imageRef.lastIndexOf(':');
        return colon > slash ? imageRef.substring(0, colon) : imageRef;
    }

    // --- comparison (unit-tested) ----------------------------------------------

    /** Human-readable mismatch lines; empty when {@code labels} and {@code other} agree. */
    static List<String> mismatches(Versions labels, Versions other, String otherName) {
        List<String> problems = new ArrayList<>();
        if (!labels.claudeCode().equals(other.claudeCode())) {
            problems.add("claude-code: label=%s %s=%s"
                    .formatted(labels.claudeCode(), otherName, other.claudeCode()));
        }
        if (!labels.shipsmooth().equals(other.shipsmooth())) {
            problems.add("shipsmooth: label=%s %s=%s"
                    .formatted(labels.shipsmooth(), otherName, other.shipsmooth()));
        }
        return problems;
    }

    // --- IO (laptop-verified) --------------------------------------------------

    private static String dockerInspect(String imageRef, CommandRunner runner) {
        // Bare `docker inspect` returns a JSON array; labelsFromInspect reads element 0.
        return runner.capture(List.of("docker", "inspect", imageRef));
    }

    private static String fetchOverview(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create(
                                        "https://hub.docker.com/v2/repositories/" + repo + "/"))
                                .header("Accept", "application/json")
                                .timeout(Duration.ofSeconds(20))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Docker Hub returned HTTP " + response.statusCode() + " for " + repo);
        }
        return MAPPER.readTree(response.body()).path("full_description").asText("");
    }

    /** Validate {@code imageRef}. {@code local=true} compares against the image itself. */
    static void validate(String imageRef, boolean local, CommandRunner runner)
            throws IOException, InterruptedException {
        Versions labels = labelsFromInspect(dockerInspect(imageRef, runner));

        List<String> problems;
        if (local) {
            String cli = runner.capture(List.of("docker", "run", "--rm", imageRef, "claude", "--version"));
            Versions runtime = new Versions(parseClaudeVersion(cli), labels.shipsmooth());
            problems = mismatches(labels, runtime, "runtime");
        } else {
            problems = mismatches(labels, versionsFromOverview(fetchOverview(repoOf(imageRef))), "Overview");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("label validation failed for " + imageRef + ":\n  "
                    + String.join("\n  ", problems));
        }
        System.err.println("label validation OK for " + imageRef + (local ? " (local)" : " (Overview)"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: <imageRef> [--local]");
        }
        boolean local = args.length > 1 && args[1].equals("--local");
        validate(args[0], local, new ProcessCommandRunner());
    }
}
