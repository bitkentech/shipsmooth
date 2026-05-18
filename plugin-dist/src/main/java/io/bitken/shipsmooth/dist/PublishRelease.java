package io.bitken.shipsmooth.dist;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class PublishRelease {

    private final String version;
    private final Path repoRoot;
    private final Path jdkHome;

    public PublishRelease(String version, Path repoRoot, Path jdkHome) {
        this.version = version;
        this.repoRoot = repoRoot;
        this.jdkHome = jdkHome;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PublishRelease <version>");
            System.err.println("Optional: -Dshipsmooth.repo.root=<repo-root> -Djdk.semeru.linux-x64=<jdk-home>");
            System.exit(1);
        }
        String version = args[0];
        Path repoRoot = Path.of(System.getProperty("shipsmooth.repo.root", System.getProperty("user.dir"))).toAbsolutePath();
        Path jdkHome = Path.of(System.getProperty("jdk.semeru.linux-x64", "/opt/installers/jdk-semeru/jdk-25.0.2+10"));
        new PublishRelease(version, repoRoot, jdkHome).run();
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
            git("checkout", originalBranch);
        }

        System.out.println("Release v" + version + " complete.");
    }

    private void bumpAndCommitVersion() throws IOException, InterruptedException {
        runCommand(List.of("mvn", "-f", repoRoot.resolve("pom.xml").toString(), "versions:set", "-DnewVersion=" + version, "-DgenerateBackupPoms=false"), repoRoot);

        List<String> addCmd = new ArrayList<>(List.of("git", "add"));
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(p -> p.getFileName().toString().equals("pom.xml"))
                  .map(p -> repoRoot.relativize(p).toString())
                  .forEach(addCmd::add);
        }
        runCommand(addCmd, repoRoot);

        // skip commit if nothing changed (version was already set)
        ProcessBuilder status = new ProcessBuilder("git", "diff", "--cached", "--quiet")
                .directory(repoRoot.toFile());
        if (status.start().waitFor() != 0) {
            git("commit", "-m", "chore: bump version to " + version);
        } else {
            System.out.println("Version already at " + version + ", skipping bump commit.");
        }
    }

    private void buildAndPackage() throws IOException, InterruptedException {
        Path buildDir = repoRoot.resolve("build");
        if (Files.exists(buildDir)) deleteDirectory(buildDir);

        runCommand(List.of("mvn", "-f", repoRoot.resolve("pom.xml").toString(),
                "-pl", "plugin-tasks-java", "-am", "-Pjlink",
                "-Dexperimental.enabled=false",
                "package"), repoRoot);

        runCommand(List.of("mvn", "-f", repoRoot.resolve("pom.xml").toString(), "compile", "-Pprod", "-P!dev"), repoRoot);

        Path outputDir = repoRoot.resolve("plugin-dist/target/dist");
        Files.createDirectories(outputDir);
        new PackageRuntime("linux-x64", jdkHome, repoRoot.resolve("plugin-tasks-java/target/jlink-image"), outputDir, version).run();
        System.out.println("Runtime zip: " + outputDir.resolve("shipsmooth-tasks-" + version + "-linux-x64.zip"));
    }

    private void syncDistAndPublish(String mainSha) throws IOException, InterruptedException {
        Path distDir = repoRoot.resolve("dist");
        if (Files.exists(distDir)) deleteDirectory(distDir);
        Files.createDirectories(distDir);

        Path buildDir = repoRoot.resolve("build");
        copyRecursive(buildDir.resolve(".claude-plugin"), distDir.resolve(".claude-plugin"));
        copyRecursive(buildDir.resolve("hooks"),         distDir.resolve("hooks"));
        copyRecursive(buildDir.resolve("dist"),          distDir.resolve("dist"));
        copyRecursive(buildDir.resolve("scripts"),       distDir.resolve("scripts"));
        copyRecursive(buildDir.resolve("skills"),        distDir.resolve("skills"));
        Files.copy(buildDir.resolve("package.json"), distDir.resolve("package.json"), StandardCopyOption.REPLACE_EXISTING);

        git("add", "dist/");
        git("commit", "-m", "release: v" + version);
        git("tag", "v" + version);
        git("push", "origin", "releases", "v" + version);

        Path zipPath = repoRoot.resolve("plugin-dist/target/dist/shipsmooth-tasks-" + version + "-linux-x64.zip");
        runCommand(List.of("gh", "release", "create", "v" + version,
                "--target", "releases", "--title", "v" + version,
                "--notes", "Release v" + version + " (main: " + mainSha + ")"), repoRoot);
        runCommand(List.of("gh", "release", "upload", "v" + version, zipPath.toString()), repoRoot);
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