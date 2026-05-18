package io.bitken.shipsmooth.dist;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
        String repoRootProp = System.getProperty("shipsmooth.repo.root", System.getProperty("user.dir"));
        Path repoRoot = Path.of(repoRootProp).toAbsolutePath();
        String jdkHomeProp = System.getProperty("jdk.semeru.linux-x64", "/opt/installers/jdk-semeru/jdk-25.0.2+10");
        Path jdkHome = Path.of(jdkHomeProp);

        new PublishRelease(version, repoRoot, jdkHome).run();
    }

    public void run() throws IOException, InterruptedException {
        String originalBranch = runCommand(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), repoRoot).strip();

        // 1. Assert clean working tree
        assertCleanWorkingTree(repoRoot);

        // 2. Assert tag does not exist
        assertTagAbsent("v" + version);

        // 3. Bump version in all pom.xml files
        runCommand(List.of("mvn", "versions:set",
                "-DnewVersion=" + version,
                "-DgenerateBackupPoms=false"), repoRoot);

        // 4. Commit version bump
        List<String> pomFiles = Files.walk(repoRoot)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .map(p -> repoRoot.relativize(p).toString())
                .collect(Collectors.toList());
        List<String> addCmd = new java.util.ArrayList<>(List.of("git", "add"));
        addCmd.addAll(pomFiles);
        runCommand(addCmd, repoRoot);
        runCommand(List.of("git", "commit", "-m", "chore: bump version to " + version), repoRoot);

        // 5. Record main SHA
        String mainSha = runCommand(List.of("git", "rev-parse", "--short", "HEAD"), repoRoot).strip();
        System.out.println("Main SHA: " + mainSha);

        // 6. Full plugin build
        runCommand(List.of("mvn", "compile", "-Pprod", "-P!dev"), repoRoot);

        // 7. Package runtime zip
        Path jlinkImage = repoRoot.resolve("plugin-tasks-java/target/jlink-image");
        Path outputDir = repoRoot.resolve("plugin-dist/target/dist");
        Files.createDirectories(outputDir);
        new PackageRuntime("linux-x64", jdkHome, jlinkImage, outputDir, version).run();
        Path zipPath = outputDir.resolve("shipsmooth-tasks-" + version + "-linux-x64.zip");
        System.out.println("Runtime zip: " + zipPath);

        // 8. Switch to releases branch
        runCommand(List.of("git", "checkout", "releases"), repoRoot);

        try {
            // 9. Sync dist/ from build output
            Path distDir = repoRoot.resolve("dist");
            if (Files.exists(distDir)) {
                deleteDirectory(distDir);
            }
            Files.createDirectories(distDir);
            Path buildDir = repoRoot.resolve("build");
            copyRecursive(buildDir.resolve(".claude-plugin"), distDir.resolve(".claude-plugin"));
            copyRecursive(buildDir.resolve("hooks"), distDir.resolve("hooks"));
            copyRecursive(buildDir.resolve("dist"), distDir.resolve("dist"));
            copyRecursive(buildDir.resolve("scripts"), distDir.resolve("scripts"));
            copyRecursive(buildDir.resolve("skills"), distDir.resolve("skills"));
            Files.copy(buildDir.resolve("package.json"), distDir.resolve("package.json"),
                    StandardCopyOption.REPLACE_EXISTING);

            // 10. Commit, tag, push releases branch and tag
            runCommand(List.of("git", "add", "dist/"), repoRoot);
            runCommand(List.of("git", "commit", "-m", "release: v" + version), repoRoot);
            runCommand(List.of("git", "tag", "v" + version), repoRoot);
            runCommand(List.of("git", "push", "origin", "releases", "v" + version), repoRoot);

            // 11. Create GitHub release
            runCommand(List.of("gh", "release", "create", "v" + version,
                    "--target", "releases",
                    "--title", "v" + version,
                    "--notes", "Release v" + version + " (main: " + mainSha + ")"), repoRoot);

            // 12. Upload runtime zip
            runCommand(List.of("gh", "release", "upload", "v" + version, zipPath.toString()), repoRoot);

        } finally {
            // 13. Return to original branch
            runCommand(List.of("git", "checkout", originalBranch), repoRoot);
        }

        System.out.println("Release v" + version + " complete.");
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
        ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain")
                .directory(repoRoot.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();
        if (!output.isBlank()) {
            throw new IllegalStateException("Working tree is not clean:\n" + output);
        }
    }

    private void assertTagAbsent(String tag) throws IOException, InterruptedException {
        ProcessBuilder local = new ProcessBuilder("git", "tag", "-l", tag)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true);
        Process localProc = local.start();
        String localOut = new String(localProc.getInputStream().readAllBytes()).strip();
        localProc.waitFor();
        if (!localOut.isEmpty()) {
            throw new IllegalStateException("Tag already exists locally: " + tag);
        }

        ProcessBuilder remote = new ProcessBuilder("git", "ls-remote", "--tags", "origin", tag)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true);
        Process remoteProc = remote.start();
        String remoteOut = new String(remoteProc.getInputStream().readAllBytes()).strip();
        remoteProc.waitFor();
        if (!remoteOut.isEmpty()) {
            throw new IllegalStateException("Tag already exists on remote: " + tag);
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
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
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
