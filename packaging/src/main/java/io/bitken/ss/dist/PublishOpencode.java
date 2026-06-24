package io.bitken.ss.dist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * plan-89: standalone publisher for the OpenCode plugin npm package
 * ({@code @bitkentech/shipsmooth-opencode}).
 *
 * <p>Extracted out of {@link PublishRelease} because npm publish needs a SEPARATE
 * credential (the {@code @bitkentech} scope) that {@code gh} auth does not cover and that
 * is not reliably present in the release env. Folding it into the main release once made a
 * missing npm token strand the GitHub/Windows releases. The main {@code publishRelease}
 * now skips this by default; a human runs the dedicated {@code publishReleaseOpenCode}
 * Gradle task with npm auth present. Re-running is safe — an already-published version is
 * an idempotent no-op.
 */
public class PublishOpencode {

    /** Human-readable npm coordinate, used only in status messages. */
    private static final String PACKAGE = "@bitkentech/shipsmooth-opencode";
    /** Assembled prod payload dir (where {@code assembleOpencodeProd} writes). */
    private static final String PAYLOAD_DIR = "build-opencode";

    private final String version;
    private final Path repoRoot;
    private final Path payload;

    public PublishOpencode(String version, Path repoRoot) {
        this.version = version;
        this.repoRoot = repoRoot;
        this.payload = repoRoot.resolve(PAYLOAD_DIR);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PublishOpencode <version>");
            System.err.println("Optional: -Dshipsmooth.repo.root=<path>");
            System.exit(1);
        }
        String version = args[0];
        Path repoRoot = Path.of(System.getProperty("shipsmooth.repo.root",
                System.getProperty("user.dir"))).toAbsolutePath();
        new PublishOpencode(version, repoRoot).run();
    }

    /** Assemble the prod payload, validate it (fail-fast), then idempotent {@code npm publish}. */
    public void run() throws IOException, InterruptedException {
        PublishRelease.runCommand(assembleOpencodeCommand(repoRoot), repoRoot);
        ValidateRelease.validateOpencode(payload);
        publishOpencode();
    }

    /**
     * Assemble the opencode prod payload into {@link #PAYLOAD_DIR} under the repo root. The
     * {@code -Pbuild.outputDir} is required: {@code assembleOpencodeProd} otherwise defaults
     * to the dev payload dir, so the publish and validate would read a different (empty) dir.
     */
    static List<String> assembleOpencodeCommand(Path repoRoot) {
        return List.of(repoRoot.resolve("gradlew").toString(), "assembleOpencodeProd",
                "-Pbuild.outputDir=" + repoRoot.resolve(PAYLOAD_DIR));
    }

    private void publishOpencode() throws IOException, InterruptedException {
        try {
            PublishRelease.runCommand(npmPublishCommand(repoRoot), repoRoot);
            System.out.println("OpenCode plugin published to npm: " + PACKAGE + "@" + version);
        } catch (IOException e) {
            if (!isAlreadyPublished(e.getMessage())) throw e;
            System.out.println("OpenCode plugin " + PACKAGE + "@" + version
                    + " already published — skipping (idempotent no-op).");
        }
    }

    /**
     * The {@code npm publish} command for the assembled opencode payload. {@code npm}
     * reads the payload's {@code package.json} {@code files}/{@code publishConfig}.
     */
    static List<String> npmPublishCommand(Path repoRoot) {
        return List.of("npm", "publish", repoRoot.resolve(PAYLOAD_DIR).toString());
    }

    /**
     * Classifies {@code npm publish} output as the idempotent "this version is already on
     * the registry" case (npm {@code E403} / "cannot publish over the previously published
     * versions"). Returns {@code true} only for that case — auth failures ({@code E401}),
     * missing-scope, and network errors return {@code false} and stay hard failures, so a
     * human re-running the task still learns when auth is actually missing.
     */
    static boolean isAlreadyPublished(String npmOutput) {
        if (npmOutput == null) return false;
        String s = npmOutput.toLowerCase();
        return s.contains("cannot publish over the previously published")
                || s.contains("epublishconflict")
                || (s.contains("e403") && s.contains("previously published"));
    }
}
