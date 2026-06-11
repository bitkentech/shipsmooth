package io.bitken.ss.dist;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class PublishRelease {

    /**
     * The {@code build/} subdirectories copied into the {@code releases}-branch
     * {@code dist/} payload. The jlink-runtime restructure (plans 68/69) replaced
     * the shipped Node CLI, so the build no longer emits {@code scripts/} or
     * {@code package.json} (the SessionStart hook runs {@code dist/session-start.js}
     * directly). Keep this in sync with the build output.
     */
    static final List<String> SHIPPED_BUILD_SUBPATHS =
            List.of(".claude-plugin", "hooks", "dist", "skills");

    private final String version;
    private final Path repoRoot;
    private final Path linuxJdkHome;
    private final Path darwinX64JdkHome;
    private final Path darwinArm64JdkHome;
    private final Path windowsX64JdkHome;
    private final Path windowsRepoPath;
    private final boolean skipValidation;

    public PublishRelease(String version, Path repoRoot, Path linuxJdkHome, Path darwinX64JdkHome, Path darwinArm64JdkHome, Path windowsX64JdkHome) {
        this(version, repoRoot, linuxJdkHome, darwinX64JdkHome, darwinArm64JdkHome, windowsX64JdkHome, false);
    }

    public PublishRelease(String version, Path repoRoot, Path linuxJdkHome, Path darwinX64JdkHome, Path darwinArm64JdkHome, Path windowsX64JdkHome, boolean skipValidation) {
        this(version, repoRoot, linuxJdkHome, darwinX64JdkHome, darwinArm64JdkHome, windowsX64JdkHome,
                repoRoot.getParent().resolve("shipsmooth-windows"), skipValidation);
    }

    public PublishRelease(String version, Path repoRoot, Path linuxJdkHome, Path darwinX64JdkHome, Path darwinArm64JdkHome, Path windowsX64JdkHome, Path windowsRepoPath, boolean skipValidation) {
        this.version = version;
        this.repoRoot = repoRoot;
        this.linuxJdkHome = linuxJdkHome;
        this.darwinX64JdkHome = darwinX64JdkHome;
        this.darwinArm64JdkHome = darwinArm64JdkHome;
        this.windowsX64JdkHome = windowsX64JdkHome;
        this.windowsRepoPath = windowsRepoPath;
        this.skipValidation = skipValidation;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PublishRelease <version>");
            System.err.println("Optional: -Dshipsmooth.repo.root=<path> -Dshipsmooth.windows.repo=<path>");
            System.err.println("         -Djdk.semeru.linux-x64=<path> -Djdk.semeru.darwin-x64=<path>");
            System.err.println("         -Djdk.semeru.darwin-arm64=<path> -Djdk.semeru.windows-x64=<path>");
            System.exit(1);
        }
        String version = args[0];
        boolean skipValidation = List.of(args).contains("--dangerous-skip-release-validation");
        Path repoRoot = Path.of(System.getProperty("shipsmooth.repo.root", System.getProperty("user.dir"))).toAbsolutePath();
        Path linuxJdkHome = Path.of(System.getProperty("jdk.semeru.linux-x64", "/opt/installers/jdk-semeru/jdk-25.0.2+10"));
        Path darwinX64JdkHome = Path.of(System.getProperty("jdk.semeru.darwin-x64", "/opt/installers/jdk-semeru-mac-x64/Contents/Home"));
        Path darwinArm64JdkHome = Path.of(System.getProperty("jdk.semeru.darwin-arm64", "/opt/installers/jdk-semeru-mac-arm64/Contents/Home"));
        Path windowsX64JdkHome = Path.of(System.getProperty("jdk.semeru.windows-x64", "/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10"));
        Path windowsRepoPath = Path.of(System.getProperty("shipsmooth.windows.repo", repoRoot.getParent().resolve("shipsmooth-windows").toString()));
        new PublishRelease(version, repoRoot, linuxJdkHome, darwinX64JdkHome, darwinArm64JdkHome, windowsX64JdkHome, windowsRepoPath, skipValidation).run();
    }

    public void run() throws IOException, InterruptedException {
        String originalBranch = git("rev-parse", "--abbrev-ref", "HEAD").strip();
        assertCleanWorkingTree(repoRoot);
        assertTagAbsent("v" + version);

        bumpAndCommitVersion();
        String mainSha = git("rev-parse", "--short", "HEAD").strip();
        System.out.println("Main SHA: " + mainSha);

        buildAndPackage();

        git("checkout", "releases");
        try {
            syncDistAndPublish(mainSha);
        } finally {
            git("checkout", "-f", originalBranch);
        }

        buildWindowsPlugin();
        publishWindowsRelease(mainSha);

        System.out.println("Release v" + version + " complete.");
    }

    private void bumpAndCommitVersion() throws IOException, InterruptedException {
        boolean changed = bumpVersionInGradleProperties(repoRoot, version);
        if (!changed) {
            System.out.println("Version already at " + version + ", skipping bump commit.");
            return;
        }
        git("add", "gradle.properties");
        git("commit", "-m", "chore: bump version to " + version);
    }

    /**
     * Post-cutover single source of truth for the build version: the
     * {@code plugin.version} line in {@code gradle.properties}. Replaces
     * {@code mvn versions:set} (there are no poms after Task 17). Returns
     * {@code true} if the file changed (a bump happened), {@code false} if the
     * version was already at the target.
     */
    static boolean bumpVersionInGradleProperties(Path repoRoot, String version) throws IOException {
        Path gp = repoRoot.resolve("gradle.properties");
        String content = Files.readString(gp);
        String target = "plugin.version=" + version;
        if (content.contains(target)) return false;
        String updated = content.replaceAll("(?m)^plugin\\.version=.*$", "plugin.version=" + version);
        if (updated.equals(content)) {
            throw new IllegalStateException("No plugin.version line found in " + gp);
        }
        Files.writeString(gp, updated);
        return true;
    }

    private static String gradlew(Path repoRoot) {
        return repoRoot.resolve("gradlew").toString();
    }

    /**
     * All four jlink platform images. Replaces {@code mvn -Pjlink package}. The jlink
     * tasks are now registered unconditionally (plan-74 dropped the {@code -PjlinkBuild}
     * existence gate — lazy configuration keeps them zero-cost on normal builds), so the
     * release just names the four platform tasks explicitly.
     */
    static List<String> jlinkBuildCommand(Path repoRoot) {
        return List.of(gradlew(repoRoot),
                ":cli:image_linux-x64",
                ":cli:image_darwin-x64",
                ":cli:image_darwin-arm64",
                ":cli:image_windows-x64",
                // The SINGLE prod signal (plan-75 Task 2): -Pbuild.env=prod bakes
                // EXPERIMENTAL_BUILD=false (hiding --enable-experimental from --help) via
                // core's generateBuildConstants AND routes each image into its -prod
                // folder (see cli/build.gradle.kts). No per-knob -Pexperimental.enabled —
                // future build-variant properties derive from build.env too.
                "-Pbuild.env=prod");
    }

    /** Prod claude payload into {@code build/}. Replaces {@code mvn compile -Pprod -P!dev}. */
    static List<String> assembleProdCommand(Path repoRoot) {
        return List.of(gradlew(repoRoot), "assembleClaudeProd",
                "-Pbuild.outputDir=" + repoRoot.resolve("build"));
    }

    /** Windows payload into {@code build-windows/}. Replaces {@code mvn compile -Pwindows -P!dev}. */
    static List<String> assembleWindowsCommand(Path repoRoot) {
        return List.of(gradlew(repoRoot), "assembleWindows",
                "-Pbuild.outputDir=" + repoRoot.resolve("build-windows"));
    }

    static void validateBuildOutput(Path buildDir) throws IOException {
        ValidateRelease.validate(buildDir, null);
    }

    static void maybeValidateBuildOutput(Path buildDir, boolean skip) throws IOException {
        if (skip) {
            System.out.println("WARNING: release validation skipped — --dangerous-skip-release-validation was passed");
            return;
        }
        validateBuildOutput(buildDir);
    }

    private void buildAndPackage() throws IOException, InterruptedException {
        Path buildDir = repoRoot.resolve("build");
        if (Files.exists(buildDir)) deleteDirectory(buildDir);

        runCommand(jlinkBuildCommand(repoRoot), repoRoot);

        runCommand(assembleProdCommand(repoRoot), repoRoot);
        maybeValidateBuildOutput(buildDir, skipValidation);

        Path outputDir = repoRoot.resolve("packaging/target/dist");
        Files.createDirectories(outputDir);
        new PackageRuntime("linux-x64", linuxJdkHome, jlinkImagePath(repoRoot, "linux-x64"), outputDir, version).run();
        System.out.println("Runtime zip: " + outputDir.resolve("shipsmooth-" + version + "-linux-x64.zip"));
        new PackageRuntime("darwin-x64", darwinX64JdkHome, jlinkImagePath(repoRoot, "darwin-x64"), outputDir, version).run();
        System.out.println("Runtime zip: " + outputDir.resolve("shipsmooth-" + version + "-darwin-x64.zip"));
        new PackageRuntime("darwin-arm64", darwinArm64JdkHome, jlinkImagePath(repoRoot, "darwin-arm64"), outputDir, version).run();
        System.out.println("Runtime zip: " + outputDir.resolve("shipsmooth-" + version + "-darwin-arm64.zip"));
        new PackageRuntime("win32-x64", windowsX64JdkHome, jlinkImagePath(repoRoot, "windows-x64"), outputDir, version).run();
        System.out.println("Runtime zip: " + outputDir.resolve("shipsmooth-" + version + "-win32-x64.zip"));
    }

    /**
     * Location of a platform's jlink image. The Gradle {@code image_<platform>}
     * tasks write to {@code cli/build/jlink-image-<platform>} (the old Maven build
     * wrote to {@code cli/target/...} — reading there packaged a stale image, or
     * none on a clean tree). The platform key matches the Gradle task suffix:
     * linux-x64, darwin-x64, darwin-arm64, windows-x64.
     */
    static Path jlinkImagePath(Path repoRoot, String platform) {
        // Read the PROD image (plan-75 Task 2): the prod jlink build writes to the
        // -prod folder, while a dev build writes to the non-prod folder. Reading only
        // -prod means the release can never package a stale dev image left behind in
        // cli/build — clean provenance by path, no clean task required.
        return repoRoot.resolve("cli/build/jlink-image-" + platform + "-prod");
    }

    private void buildWindowsPlugin() throws IOException, InterruptedException {
        Path buildDir = repoRoot.resolve("build-windows");
        if (Files.exists(buildDir)) deleteDirectory(buildDir);

        runCommand(assembleWindowsCommand(repoRoot), repoRoot);
        System.out.println("Windows plugin build complete: " + buildDir);
    }

    private void publishWindowsRelease(String mainSha) throws IOException, InterruptedException {
        if (!Files.exists(windowsRepoPath)) {
            throw new IllegalStateException("Windows repo not found at: " + windowsRepoPath +
                "\nClone bitkentech/shipsmooth-windows there or pass -Dshipsmooth.windows.repo=<path>");
        }

        Path buildDir = repoRoot.resolve("build-windows");

        // Wipe working tree, create fresh orphan commit — no history retained
        runCommand(List.of("git", "checkout", "--orphan", "releases-" + version), windowsRepoPath);
        runCommand(List.of("git", "rm", "-rf", "--quiet", "."), windowsRepoPath);

        // Copy plugin payload
        copyRecursive(buildDir.resolve(".claude-plugin"), windowsRepoPath.resolve(".claude-plugin"));
        copyRecursive(buildDir.resolve("hooks"),          windowsRepoPath.resolve("hooks"));
        copyRecursive(buildDir.resolve("skills"),         windowsRepoPath.resolve("skills"));
        copyRecursive(jlinkImagePath(repoRoot, "windows-x64"),
                      windowsRepoPath.resolve("runtime"));

        runCommand(List.of("git", "add", "."), windowsRepoPath);
        runCommand(List.of("git", "commit", "-m", "release: v" + version + " (main: " + mainSha + ")"), windowsRepoPath);
        runCommand(List.of("git", "branch", "-M", "releases-" + version, "main"), windowsRepoPath);
        runCommand(List.of("git", "push", "origin", "main", "--force"), windowsRepoPath);
        System.out.println("Windows release v" + version + " pushed to " + windowsRepoPath);
    }

    private void syncDistAndPublish(String mainSha) throws IOException, InterruptedException {
        Path distDir = repoRoot.resolve("dist");
        if (Files.exists(distDir)) deleteDirectory(distDir);
        Files.createDirectories(distDir);

        Path buildDir = repoRoot.resolve("build");
        for (String sub : SHIPPED_BUILD_SUBPATHS) {
            copyRecursive(buildDir.resolve(sub), distDir.resolve(sub));
        }

        git("add", "dist/");
        git("commit", "-m", "release: v" + version);
        git("tag", "v" + version);
        git("push", "origin", "releases", "v" + version);

        Path distDir2 = repoRoot.resolve("packaging/target/dist");
        runCommand(List.of("gh", "release", "create", "v" + version,
                "--target", "releases", "--title", "v" + version,
                "--notes", "Release v" + version + " (main: " + mainSha + ")"), repoRoot);
        runCommand(List.of("gh", "release", "upload", "v" + version,
                distDir2.resolve("shipsmooth-" + version + "-linux-x64.zip").toString(),
                distDir2.resolve("shipsmooth-" + version + "-darwin-x64.zip").toString(),
                distDir2.resolve("shipsmooth-" + version + "-darwin-arm64.zip").toString(),
                distDir2.resolve("shipsmooth-" + version + "-win32-x64.zip").toString()), repoRoot);
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return runCommand(cmd, repoRoot);
    }

    static String runCommand(List<String> cmd, Path workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed (exit " + exit + "): " + String.join(" ", cmd) + "\n" + output);
        }
        return output;
    }

    static void assertCleanWorkingTree(Path repoRoot) throws IOException, InterruptedException {
        // Check only tracked files — untracked files are not a release blocker
        ProcessBuilder unstaged = new ProcessBuilder("git", "diff", "--quiet")
                .directory(repoRoot.toFile());
        ProcessBuilder staged = new ProcessBuilder("git", "diff", "--cached", "--quiet")
                .directory(repoRoot.toFile());
        if (unstaged.start().waitFor() != 0 || staged.start().waitFor() != 0) {
            String status = runCommand(List.of("git", "status", "--short"), repoRoot);
            throw new IllegalStateException("Working tree has uncommitted changes:\n" + status);
        }
    }

    private void assertTagAbsent(String tag) throws IOException, InterruptedException {
        String localOut = runCommand(List.of("git", "tag", "-l", tag), repoRoot).strip();
        if (!localOut.isEmpty()) throw new IllegalStateException("Tag already exists locally: " + tag);

        String remoteOut = runCommand(List.of("git", "ls-remote", "--tags", "origin", tag), repoRoot).strip();
        if (!remoteOut.isEmpty()) throw new IllegalStateException("Tag already exists on remote: " + tag);
    }

    private void deleteDirectory(Path dir) throws IOException {
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

    private void copyRecursive(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) return;
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}