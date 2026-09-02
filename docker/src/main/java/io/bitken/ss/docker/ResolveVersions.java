package io.bitken.ss.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resolves the component versions baked into the sandbox image.
 *
 * <p>Claude Code is installed unpinned in the Dockerfile, so what lands in the image
 * depends on when it was built. This resolves the npm {@code stable} dist-tag on the
 * build host so the Dockerfile can install that exact version and record it as a label.
 *
 * <p>The shipsmooth version is a manual pin ({@code shipsmoothVersion} in
 * {@code gradle.properties}) for now — the plugin marketplace has no simple
 * version endpoint, and plugin + CLI runtime share one number today. Revisit if
 * they diverge.
 *
 * <p>de-risk draft (Task 2): the JSON parse is unit-tested against a fixture; the
 * HTTP fetch and the {@code main} wiring are exercised by the {@code resolveVersions}
 * Gradle task and, end to end, by the laptop smoke test.
 */
public final class ResolveVersions {

    static final String REGISTRY_URL = "https://registry.npmjs.org/@anthropic-ai/claude-code";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResolveVersions() {
    }

    /** The {@code stable} dist-tag from a claude-code npm registry document. */
    static String claudeCodeStable(String registryJson) throws IOException {
        return distTag(registryJson, "stable");
    }

    /** An arbitrary dist-tag from an npm registry document. Throws if absent. */
    static String distTag(String registryJson, String tag) throws IOException {
        JsonNode distTags = MAPPER.readTree(registryJson).path("dist-tags");
        JsonNode value = distTags.get(tag);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException(
                    "npm registry document has no '" + tag + "' dist-tag");
        }
        return value.asText();
    }

    /** GET the claude-code registry document. */
    static String fetchRegistry() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(REGISTRY_URL))
                // Abbreviated ("corgi") metadata — still carries dist-tags but drops
                // most per-version detail, so the response is a fraction of the size.
                .header("Accept", "application/vnd.npm.install-v1+json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("npm registry returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Component versions to bake into the image. */
    public record Versions(String claudeCode, String shipsmooth) {

        /** {@code claude-<claudeCode>-ss-<shipsmooth>} — the compound image tag. */
        public String compoundTag() {
            return "claude-" + claudeCode + "-ss-" + shipsmooth;
        }
    }

    /** Resolve claude-code from npm, pair it with the supplied shipsmooth version. */
    public static Versions resolve(String shipsmoothVersion) throws IOException, InterruptedException {
        return new Versions(claudeCodeStable(fetchRegistry()), shipsmoothVersion);
    }

    /**
     * Prints {@code key=value} lines for the Gradle build to capture.
     * Arg 0 (or {@code -Dshipsmooth.version}) is the shipsmooth version.
     */
    public static void main(String[] args) throws Exception {
        String shipsmooth = args.length > 0
                ? args[0]
                : System.getProperty("shipsmooth.version", "");
        if (shipsmooth.isBlank()) {
            throw new IllegalArgumentException(
                    "shipsmooth version required (arg 0 or -Dshipsmooth.version)");
        }
        Versions v = resolve(shipsmooth);
        System.out.println("claude-code=" + v.claudeCode());
        System.out.println("shipsmooth=" + v.shipsmooth());
        System.out.println("compound-tag=" + v.compoundTag());
    }
}
