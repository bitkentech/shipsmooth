package io.bitken.ss.dist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Publishes a shipsmooth-beta release (plan-115, 05 §6): the Rust CLI as its own
 * product, on its own release line. A separate entry point from {@link PublishRelease}
 * — never a branch inside it — so a Rust failure can never strand a Java release, nor
 * the reverse.
 *
 * <p>Every check runs before anything outward:
 * <ol>
 *   <li>the gate (off by default; {@code -PpublishBeta=true});</li>
 *   <li>a clean tracked working tree;</li>
 *   <li>{@code v<version>} absent locally and on origin;</li>
 *   <li>the linux-x64 binary's {@code --version} equals the release version — the beta's
 *       replacement for {@link ReleaseGuard}, which disassembles jlink images and cannot
 *       read a native binary.</li>
 * </ol>
 * Then: package the zips, commit the assembled plugin payload to {@code dist/} on the
 * {@code releases-beta} branch (via a throwaway worktree, so the caller's checkout is
 * never switched), tag and push it, create the GitHub release as a pre-release that is
 * never marked latest, and confirm by artifact with {@code gh release view}.
 *
 * <p>No version bump commit: the version is the Cargo workspace version, committed by a
 * human beforehand. The release notes carry {@code Release v<ver> (main: <sha>)}, the
 * same provenance line as the Java release, so any parity claim is reproducible.
 */
public class PublishBetaRelease {

    static final String BRANCH = "releases-beta";

    /** Runs one command in {@code dir}, returning its output; throws on a non-zero exit. */
    @FunctionalInterface
    interface CommandRunner {
        String run(List<String> cmd, Path dir) throws IOException, InterruptedException;
    }

    /**
     * @param enabled     the publish gate (off unless explicitly opted in)
     * @param version     the Cargo workspace version, e.g. {@code 0.1.0-beta}
     * @param repoRoot    the shipsmooth checkout to release from
     * @param binDir      exp/rust's {@code betaBinaries} output ({@code <platform>/shipsmooth-beta})
     * @param payloadDir  the assembled plugin payload ({@code assembleClaudeBeta})
     * @param outputDir   where the release zips are written
     * @param worktreeDir a not-yet-existing path for the {@code releases-beta} worktree
     */
    record Config(boolean enabled, String version, Path repoRoot, Path binDir, Path payloadDir,
                  Path outputDir, Path worktreeDir) {
        String tag() {
            return "v" + version;
        }
    }

    private final Config config;
    private final CommandRunner runner;

    PublishBetaRelease(Config config, CommandRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: PublishBetaRelease <version> <repoRoot> <binDir> <payloadDir> <outputDir> [--publish-beta]");
            System.exit(1);
        }
        Path outputDir = Path.of(args[4]);
        Config config = new Config(List.of(args).contains("--publish-beta"), args[0], Path.of(args[1]),
            Path.of(args[2]), Path.of(args[3]), outputDir, outputDir.resolve("releases-beta-worktree"));
        new PublishBetaRelease(config, PublishRelease::runCommand).run();
    }

    public void run() throws IOException, InterruptedException {
        if (!config.enabled()) {
            throw new IllegalStateException("Publishing shipsmooth-beta is gated off by default. "
                + "It publishes outward (a tag, the " + BRANCH + " branch, a GitHub release); "
                + "re-run with -PpublishBeta=true to proceed.");
        }
        assertCleanTrackedTree();
        assertTagAbsent();
        assertBinaryVersion();

        List<Path> zips = new PackageBeta(config.version(), config.binDir(), config.outputDir()).run();
        String mainSha = git(config.repoRoot(), "rev-parse", "--short", "HEAD").strip();
        System.out.println("Main SHA: " + mainSha);

        publishPayloadBranch(mainSha);
        createRelease(mainSha, zips);
        verifyRelease();
        System.out.println("shipsmooth-beta " + config.tag() + " published.");
    }

    private void assertCleanTrackedTree() throws IOException, InterruptedException {
        String status = git(config.repoRoot(), "status", "--porcelain", "--untracked-files=no").strip();
        if (!status.isEmpty()) {
            throw new IllegalStateException("Working tree has uncommitted changes:\n" + status);
        }
    }

    private void assertTagAbsent() throws IOException, InterruptedException {
        if (!git(config.repoRoot(), "tag", "-l", config.tag()).strip().isEmpty()) {
            throw new IllegalStateException("Tag already exists locally: " + config.tag());
        }
        if (!git(config.repoRoot(), "ls-remote", "--tags", "origin", config.tag()).strip().isEmpty()) {
            throw new IllegalStateException("Tag already exists on remote: " + config.tag());
        }
    }

    private void assertBinaryVersion() throws IOException, InterruptedException {
        Path linux = PackageBeta.binary(config.binDir(), "linux-x64");
        String printed = runner.run(List.of(linux.toString(), "--version"), config.repoRoot()).strip();
        if (!printed.equals(config.version())) {
            throw new IllegalStateException("linux-x64 binary reports version '" + printed
                + "', expected '" + config.version() + "' — rebuild with `./gradlew betaBinaries`");
        }
    }

    /**
     * Commits the payload to {@code dist/} on {@code releases-beta}, tags that commit and
     * pushes both. Works in a throwaway worktree; creates the branch as an orphan on the
     * first beta release.
     */
    private void publishPayloadBranch(String mainSha) throws IOException, InterruptedException {
        Path repo = config.repoRoot();
        Path worktree = config.worktreeDir();
        boolean branchExists = !git(repo, "ls-remote", "--heads", "origin", BRANCH).strip().isEmpty();
        if (branchExists) {
            git(repo, "fetch", "origin", BRANCH);
            git(repo, "worktree", "add", "-B", BRANCH, worktree.toString(), "FETCH_HEAD");
        } else {
            git(repo, "worktree", "add", "--orphan", "-b", BRANCH, worktree.toString());
        }
        try {
            Path dist = worktree.resolve("dist");
            deleteTree(dist);
            copyTree(config.payloadDir(), dist);
            git(worktree, "add", "-A", "dist");
            git(worktree, "commit", "-m", "release: " + config.tag() + " (main: " + mainSha + ")");
            git(worktree, "tag", config.tag());
            git(worktree, "push", "origin", BRANCH, config.tag());
        } finally {
            git(repo, "worktree", "remove", "--force", worktree.toString());
        }
    }

    private void createRelease(String mainSha, List<Path> zips) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("gh", "release", "create", config.tag(),
            "--target", BRANCH,
            "--title", "shipsmooth-beta " + config.tag(),
            "--prerelease",
            // A beta must never become the repo's "latest" release: that is what users of
            // the Java product land on from the releases page.
            "--latest=false",
            "--notes", "Release " + config.tag() + " (main: " + mainSha + ")"));
        zips.forEach(zip -> cmd.add(zip.toString()));
        runner.run(cmd, config.repoRoot());
    }

    /** Confirm by artifact, never by exit code (05 §Verification). */
    private void verifyRelease() throws IOException, InterruptedException {
        String json = runner.run(List.of("gh", "release", "view", config.tag(),
            "--json", "isDraft,isPrerelease,assets"), config.repoRoot());
        JsonNode release = new ObjectMapper().readTree(json);
        Set<String> assets = new HashSet<>();
        release.path("assets").forEach(asset -> assets.add(asset.path("name").asText()));
        List<String> missing = PackageBeta.PLATFORMS.stream()
            .map(platform -> PackageBeta.assetName(config.version(), platform))
            .filter(name -> !assets.contains(name))
            .toList();
        if (release.path("isDraft").asBoolean(true) || !release.path("isPrerelease").asBoolean(false)
                || !missing.isEmpty()) {
            throw new IllegalStateException("Release " + config.tag() + " is not a complete, published "
                + "pre-release (missing assets: " + missing + "): " + json);
        }
    }

    private String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return runner.run(cmd, dir);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Copies {@code src} to {@code dst}, preserving the executable bit (the installer script). */
    private static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) {
            throw new IllegalStateException("missing assembled payload: " + src
                + " (run `./gradlew :harness:claude:assembleClaudeBeta`)");
        }
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
