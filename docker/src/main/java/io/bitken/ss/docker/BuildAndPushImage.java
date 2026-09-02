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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builds the sandbox image and, for {@code build-and-push}, publishes it to Docker Hub.
 *
 * <p>{@code build-and-push} is outward-facing and hard to undo — a published tag can be
 * overwritten but not cleanly unpulled. It is wired to a WIRED-ONLY Gradle task and must
 * be run deliberately. The order is: check login → push Overview → build → push image →
 * validate labels. Overview-first is intentional: a failed Overview push aborts before
 * anything is published; the alternative (image first) can leave a published image whose
 * page describes the previous build.
 *
 * <p>The pure command construction lives in {@link ImagePlan}; this class only
 * orchestrates and talks to the Docker Hub HTTP API.
 */
public final class BuildAndPushImage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HUB_API = "https://hub.docker.com/v2";

    private BuildAndPushImage() {
    }

    // --- build / push orchestration ------------------------------------------------

    static void buildImage(ImagePlan plan, CommandRunner runner) {
        int code = runner.run(plan.buildArgv());
        if (code != 0) {
            throw new IllegalStateException("docker build failed (exit " + code + ")");
        }
    }

    static void push(ImagePlan plan, CommandRunner runner) {
        for (List<String> argv : plan.pushArgvs()) {
            int code = runner.run(argv);
            if (code != 0) {
                throw new IllegalStateException("docker push failed (exit " + code + ") for " + argv);
            }
        }
    }

    // --- login check --------------------------------------------------------------

    /** True if {@code config.json} carries a Docker Hub credential (auths entry or a helper). */
    static boolean hasDockerHubAuth(String configJson) {
        try {
            JsonNode root = MAPPER.readTree(configJson);
            if (root.hasNonNull("credsStore")) {
                return true;
            }
            JsonNode auths = root.path("auths");
            for (var it = auths.fieldNames(); it.hasNext(); ) {
                String registry = it.next();
                if (registry.contains("docker.io") || registry.equals("https://index.docker.io/v1/")) {
                    return true;
                }
            }
            JsonNode credHelpers = root.path("credHelpers");
            return credHelpers.has("https://index.docker.io/v1/") || credHelpers.has("registry-1.docker.io");
        } catch (IOException e) {
            return false;
        }
    }

    private static void assertDockerHubAuth() {
        Path config = dockerConfigPath();
        String json = "{}";
        try {
            if (Files.exists(config)) {
                json = Files.readString(config);
            }
        } catch (IOException e) {
            // fall through to the not-logged-in message
        }
        if (!hasDockerHubAuth(json)) {
            throw new IllegalStateException(
                    "not logged in to Docker Hub — run `docker login` before build-and-push "
                            + "(checked " + config + ")");
        }
    }

    private static Path dockerConfigPath() {
        String dockerConfig = System.getenv("DOCKER_CONFIG");
        Path dir = dockerConfig != null && !dockerConfig.isBlank()
                ? Path.of(dockerConfig)
                : Path.of(System.getProperty("user.home"), ".docker");
        return dir.resolve("config.json");
    }

    // --- Docker Hub Overview ------------------------------------------------------

    /** The markdown pushed to the repository's Overview (the "visible" version channel). */
    static String renderOverview(String repo, Versions versions) {
        return """
                # shipsmooth-claude sandbox

                Ubuntu + Node + Claude Code + the shipsmooth plugin, ready to pull-and-run.

                ```
                docker pull %s:latest
                docker run -dit -v $(pwd):/workspace %s:latest
                docker exec -it <container> bash   # then: claude
                ```

                ## Component versions in `:latest`

                | Component | Version |
                |---|---|
                | Claude Code (`@anthropic-ai/claude-code`) | `%s` |
                | shipsmooth plugin | `%s` |

                Authoritative versions live in the image's OCI labels (the table above is
                pushed separately and can lag by one build):

                ```
                docker inspect --format '{{json .Config.Labels}}' %s:latest
                ```

                Also tagged `%s` (immutable, per-build).
                """
                .formatted(
                        repo, repo,
                        versions.claudeCode(), versions.shipsmooth(),
                        repo, versions.compoundTag());
    }

    /**
     * Renders the Overview to {@code build/overview.md} and tries to PATCH it onto the
     * repository. Best-effort: the Docker Hub API only accepts a full-scope JWT from
     * username+password login (no PAT scope covers {@code full_description}, and 2FA
     * needs an interactive exchange), so on any auth/API failure this logs a "paste it
     * by hand" note and returns rather than aborting the publish.
     */
    private static void publishOverview(String repo, String markdown) throws IOException {
        Path rendered = Path.of(System.getProperty("user.dir"), "build", "overview.md");
        Files.createDirectories(rendered.getParent());
        Files.writeString(rendered, markdown);

        String user = System.getenv("DOCKERHUB_USERNAME");
        String secret = System.getenv("DOCKERHUB_TOKEN");
        try {
            if (user == null || user.isBlank() || secret == null || secret.isBlank()) {
                throw new IOException("DOCKERHUB_USERNAME / DOCKERHUB_TOKEN not set");
            }
            patchOverview(repo, markdown, user, secret);
            System.err.println("Overview updated for " + repo);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("NOTE: skipped the Docker Hub Overview (" + e.getMessage() + ").");
            System.err.println("      Paste " + rendered + " into");
            System.err.println("      https://hub.docker.com/r/" + repo + " -> Edit -> Repository overview");
        }
    }

    private static void patchOverview(String repo, String markdown, String user, String secret)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String loginBody = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("username", user).put("password", secret));
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(HUB_API + "/users/login/"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (login.statusCode() != 200) {
            throw new IOException("login HTTP " + login.statusCode() + " — " + login.body());
        }
        String jwt = MAPPER.readTree(login.body()).path("token").asText();
        if (jwt.isBlank()) {
            throw new IOException("login returned no token (2FA account?)");
        }

        String patchBody = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("full_description", markdown));
        HttpResponse<String> patch = client.send(
                HttpRequest.newBuilder(URI.create(HUB_API + "/repositories/" + repo + "/"))
                        .header("Content-Type", "application/json")
                        // hub.docker.com's v2 API expects the "JWT" scheme, not "Bearer".
                        .header("Authorization", "JWT " + jwt)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (patch.statusCode() / 100 != 2) {
            throw new IOException("PATCH HTTP " + patch.statusCode() + " — " + patch.body()
                    + " (needs a full-scope JWT: username+password login, not a PAT)");
        }
    }

    // --- entrypoint --------------------------------------------------------------

    /**
     * {@code build <repo> <shipsmoothVersion> [explicitTag]} — build only, no push.
     * {@code build-and-push <repo> <shipsmoothVersion>} — build, push image + Overview.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: <build|build-and-push> <repo> <shipsmoothVersion> [explicitTag]");
        }
        String mode = args[0];
        String repo = args[1];
        String shipsmoothVersion = args[2];
        String explicitTag = args.length > 3 && !args[3].isBlank() ? args[3] : null;

        CommandRunner runner = new ProcessCommandRunner();
        Versions versions = ResolveVersions.resolve(shipsmoothVersion);
        String vcsRef = gitSha();
        String buildDate = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String projectRoot = System.getProperty("user.dir");
        String dockerfile = Path.of(projectRoot, "Dockerfile").toString();

        ImagePlan plan = explicitTag != null
                ? ImagePlan.forExplicitTag(explicitTag, versions, projectRoot, dockerfile, vcsRef, buildDate)
                : ImagePlan.forStandardTags(repo, versions, projectRoot, dockerfile, vcsRef, buildDate);

        switch (mode) {
            case "build" -> buildImage(plan, runner);
            case "build-and-push" -> {
                assertDockerHubAuth();
                publishOverview(repo, renderOverview(repo, versions));
                buildImage(plan, runner);
                push(plan, runner);
                // Local check always holds; the Overview may be pending a manual paste,
                // so verify that separately with `./gradlew validateLabels`.
                ValidateLabels.validate(repo + ":latest", true, runner);
                System.err.println("Pushed. Once the Overview is current, run: ./gradlew validateLabels");
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static String gitSha() {
        try {
            // Full 40-char SHA — org.opencontainers.image.revision convention.
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !out.isBlank() ? out : "unknown";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }
}
