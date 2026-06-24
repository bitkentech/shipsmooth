package io.bitken.ss.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * plan-89: standalone publisher for the OpenCode plugin npm package
 * ({@code @bitkentech/shipsmooth-opencode}).
 *
 * <p>Extracted out of {@link PublishRelease} because npm publish needs a SEPARATE
 * credential (the {@code @bitkentech} scope) that {@code gh} auth does not cover and that
 * is not reliably present in the release env. Folding it into the main release made a
 * missing npm token strand the GitHub/Windows releases. Now the main {@code publishRelease}
 * skips this by default and a human runs the dedicated {@code publishReleaseOpenCode}
 * Gradle task with npm auth present.
 *
 * <p>OUTWARD-facing: runs {@code npm publish} on the assembled {@code build-opencode/}
 * payload, which honours the manifest's {@code files} allowlist + {@code publishConfig}.
 * Only the prod payload ships to npm; the dev variant is filesystem-only.
 */
public class PublishOpencode {

    private final String version;
    private final Path repoRoot;

    public PublishOpencode(String version, Path repoRoot) {
        this.version = version;
        this.repoRoot = repoRoot;
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

    /**
     * The {@code npm publish} command for the assembled opencode payload. Operates on the
     * {@code build-opencode/} dir (where {@code assembleOpencodeProd} writes); {@code npm}
     * reads its {@code package.json} {@code files}/{@code publishConfig}.
     */
    static List<String> npmPublishCommand(Path repoRoot) {
        return List.of("npm", "publish", repoRoot.resolve("build-opencode").toString());
    }

    /**
     * Classifies {@code npm publish} output as the idempotent "this version is already on
     * the registry" case (npm {@code E403} / "cannot publish over the previously published
     * versions"). Returns {@code true} only for that case — auth failures ({@code E401}),
     * missing-scope, network errors, etc. return {@code false} and stay hard failures, so a
     * human re-running the task still learns when auth is actually missing.
     */
    static boolean isAlreadyPublished(String npmOutput) {
        if (npmOutput == null) return false;
        String s = npmOutput.toLowerCase();
        return s.contains("cannot publish over the previously published")
                || s.contains("you cannot publish over the previously published version")
                || s.contains("epublishconflict")
                || (s.contains("e403") && s.contains("previously published"));
    }

    /**
     * Assemble-check + idempotent {@code npm publish}. Throws if the payload was not
     * assembled, or on a real npm failure; an already-published version is a logged no-op.
     */
    public void run() throws IOException, InterruptedException {
        Path payload = repoRoot.resolve("build-opencode");
        if (!Files.exists(payload.resolve("package.json"))) {
            throw new IllegalStateException("opencode payload not assembled at " + payload
                    + " — run assembleOpencodeProd first (the publishReleaseOpenCode task does).");
        }
        try {
            PublishRelease.runCommand(npmPublishCommand(repoRoot), repoRoot);
            System.out.println("OpenCode plugin published to npm: @bitkentech/shipsmooth-opencode@"
                    + version);
        } catch (IOException e) {
            if (isAlreadyPublished(e.getMessage())) {
                System.out.println("OpenCode plugin @bitkentech/shipsmooth-opencode@" + version
                        + " already published — skipping (idempotent no-op).");
                return;
            }
            throw e;
        }
    }
}
