package io.bitken.ss.dist;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Packages the shipsmooth-beta release assets (plan-115): one zip per POSIX platform,
 * each holding exactly {@code bin/shipsmooth-beta} (mode 0755) — the single static Rust
 * binary that {@code install-shipsmooth-beta.sh} unpacks. Deliberately NOT an extension of
 * {@link PackageRuntime}: the Java packager wraps a jlink {@code runtime/} tree in an
 * SCC-warming launcher script, none of which applies to one native binary.
 *
 * <p>Reads the cross-compiled binaries from {@code <binDir>/<platform>/shipsmooth-beta}
 * (exp/rust's {@code betaBinaries} output). Windows is out of scope: the beta is POSIX-only.
 */
public class PackageBeta {

    /** The installer's platform ids, in release-asset order. */
    static final List<String> PLATFORMS = List.of("linux-x64", "darwin-x64", "darwin-arm64");

    static final String LAUNCHER = "shipsmooth-beta";

    private final String version;
    private final Path binDir;
    private final Path outputDir;

    public PackageBeta(String version, Path binDir, Path outputDir) {
        this.version = version;
        this.binDir = binDir;
        this.outputDir = outputDir;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: PackageBeta <version> <binDir> <outputDir>");
        }
        new PackageBeta(args[0], Path.of(args[1]), Path.of(args[2])).run()
            .forEach(zip -> System.out.println("Beta zip: " + zip));
    }

    /** The zip install-shipsmooth-beta.sh downloads for {@code platform}. */
    static String assetName(String version, String platform) {
        return LAUNCHER + "-" + version + "-" + platform + ".zip";
    }

    /** The cross-compiled binary for {@code platform}. */
    static Path binary(Path binDir, String platform) {
        return binDir.resolve(platform).resolve(LAUNCHER);
    }

    /** Writes every platform's zip and returns their paths, in {@link #PLATFORMS} order. */
    public List<Path> run() throws IOException {
        Files.createDirectories(outputDir);
        List<Path> zips = new ArrayList<>();
        for (String platform : PLATFORMS) {
            zips.add(zip(platform));
        }
        return zips;
    }

    private Path zip(String platform) throws IOException {
        Path binary = binary(binDir, platform);
        if (!Files.isRegularFile(binary)) {
            throw new IllegalStateException("missing " + platform + " binary: " + binary
                + " (run `./gradlew betaBinaries` in exp/rust)");
        }
        Path zipPath = outputDir.resolve(assetName(version, platform));
        try (OutputStream out = Files.newOutputStream(zipPath);
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("bin/" + LAUNCHER);
            entry.setUnixMode(UnixStat.FILE_FLAG | 0755);
            entry.setSize(Files.size(binary));
            zos.putArchiveEntry(entry);
            Files.copy(binary, zos);
            zos.closeArchiveEntry();
        }
        return zipPath;
    }
}
