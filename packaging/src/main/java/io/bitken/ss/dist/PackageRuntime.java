package io.bitken.ss.dist;

import io.bitken.ss.resources.Os;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public class PackageRuntime {

    private final Os os;
    private final Path jdkHome;
    private final Path jlinkImage;
    private final Path outputDir;
    private final String version;
    private final String target;

    public PackageRuntime(String target, Path jdkHome, Path jlinkImage, Path outputDir, String version) {
        this.target = target;
        this.os = Os.fromPackagingTarget(target);
        this.jdkHome = jdkHome;
        this.jlinkImage = jlinkImage;
        this.outputDir = outputDir;
        this.version = version;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PackageRuntime <target> <jdk-home>");
            System.err.println("Required: -Dshipsmooth.repo.root=<repo-root>");
            System.exit(1);
        }
        String target = args[0];
        Path jdkHome = Path.of(args[1]);
        String repoRootProp = System.getProperty("shipsmooth.repo.root");
        if (repoRootProp == null) {
            System.err.println("Error: -Dshipsmooth.repo.root=<repo-root> is required");
            System.exit(1);
        }
        Path repoRoot = Path.of(repoRootProp);
        Path jlinkImage = repoRoot.resolve("cli/target/jlink-image");
        Path outputDir = repoRoot.resolve("packaging/target/dist");
        String version = System.getProperty("project.version", "0.3.0");

        Files.createDirectories(outputDir);
        new PackageRuntime(target, jdkHome, jlinkImage, outputDir, version).run();
    }

    public void run() throws IOException {
        if (!Files.exists(jlinkImage.resolve("bin/shipsmooth"))) {
            throw new IllegalStateException("jlink image not found at: " + jlinkImage);
        }

        String launcherName = "bin/" + os.launcherFileName();

        Path zipPath = outputDir.resolve("shipsmooth-" + version + "-" + target + ".zip");
        try (OutputStream fos = Files.newOutputStream(zipPath);
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(fos)) {

            byte[] launcher = buildLauncher().getBytes();
            ZipArchiveEntry launcherEntry = new ZipArchiveEntry(launcherName);
            if (os instanceof Os.Posix) {
                launcherEntry.setUnixMode(UnixStat.FILE_FLAG | 0755);
            }
            launcherEntry.setSize(launcher.length);
            zos.putArchiveEntry(launcherEntry);
            zos.write(launcher);
            zos.closeArchiveEntry();

            Files.walkFileTree(jlinkImage, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = "runtime/" + jlinkImage.relativize(file).toString().replace('\\', '/');
                    ZipArchiveEntry entry = new ZipArchiveEntry(relative);
                    int mode = Files.isExecutable(file) ? 0755 : 0644;
                    entry.setUnixMode(UnixStat.FILE_FLAG | mode);
                    entry.setSize(attrs.size());
                    zos.putArchiveEntry(entry);
                    Files.copy(file, zos);
                    zos.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private String buildLauncher() {
        if (os instanceof Os.Windows) {
            return buildWindowsLauncher();
        }
        return buildPosixLauncher();
    }

    private String buildWindowsLauncher() {
        return "@echo off\r\n"
             + "set \"DIR=%~dp0\"\r\n"
             + "set \"INSTALL=%DIR%..\"\r\n"
             + "set \"SCC_DIR=%LOCALAPPDATA%\\shipsmooth\\scc\"\r\n"
             + "if not exist \"%SCC_DIR%\" mkdir \"%SCC_DIR%\"\r\n"
             + "\"%INSTALL%\\runtime\\bin\\" + os.javaExe() + "\" ^\r\n"
             + "  -Xquickstart ^\r\n"
             + "  -Xshareclasses:name=shipsmooth_v" + version + ",cacheDir=\"%SCC_DIR%\",nonfatal ^\r\n"
             + "  -m io.bitken.ss/io.bitken.ss.cli.Shipsmooth %*\r\n";
    }

    private String buildPosixLauncher() {
        return """
                #!/bin/sh
                DIR="$(cd "$(dirname "$0")" && pwd)"
                INSTALL="$(cd "$DIR/.." && pwd)"
                SCC_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/scc"
                mkdir -p "$SCC_DIR"
                exec "$INSTALL/runtime/bin/%s" \\
                  -Xquickstart \\
                  -Xshareclasses:name=shipsmooth_v%s,cacheDir="$SCC_DIR",nonfatal \\
                  -m io.bitken.ss/io.bitken.ss.cli.Shipsmooth "$@"
                """.formatted(os.javaExe(), version);
    }
}
