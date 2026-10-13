package io.bitken.ss.dist;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackageBetaTest {

    @TempDir
    Path tmp;

    /** plan-115: one zip per POSIX platform holding exactly bin/shipsmooth-beta, mode 0755. */
    @Test
    void zipsEachPlatformBinaryAsTheSoleExecutableEntry() throws Exception {
        Path binDir = tmp.resolve("beta-bin");
        for (String platform : PackageBeta.PLATFORMS) {
            Path bin = Files.createDirectories(binDir.resolve(platform)).resolve("shipsmooth-beta");
            Files.writeString(bin, "binary-for-" + platform);
        }
        Path out = tmp.resolve("dist");

        List<Path> zips = new PackageBeta("0.1.0-beta", binDir, out).run();

        assertEquals(List.of(
            out.resolve("shipsmooth-beta-0.1.0-beta-linux-x64.zip"),
            out.resolve("shipsmooth-beta-0.1.0-beta-darwin-x64.zip"),
            out.resolve("shipsmooth-beta-0.1.0-beta-darwin-arm64.zip")), zips);
        for (String platform : PackageBeta.PLATFORMS) {
            try (ZipFile zip = ZipFile.builder()
                    .setPath(out.resolve("shipsmooth-beta-0.1.0-beta-" + platform + ".zip")).get()) {
                List<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
                assertEquals(1, entries.size(), platform + ": exactly one entry");
                ZipArchiveEntry entry = entries.get(0);
                assertEquals("bin/shipsmooth-beta", entry.getName());
                assertEquals(0755, entry.getUnixMode() & 0777, platform + ": launcher must be 0755");
                assertEquals("binary-for-" + platform, new String(zip.getInputStream(entry).readAllBytes()));
            }
        }
    }

    @Test
    void aMissingPlatformBinaryFailsWithTheBuildHint() throws Exception {
        Path binDir = tmp.resolve("beta-bin");
        Files.writeString(Files.createDirectories(binDir.resolve("linux-x64")).resolve("shipsmooth-beta"), "x");

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new PackageBeta("0.1.0-beta", binDir, tmp.resolve("dist")).run());
        assertTrue(e.getMessage().contains("darwin-x64") && e.getMessage().contains("betaBinaries"), e.getMessage());
    }

    @Test
    void main_packagesFromArgs_andRejectsBadUsage() throws Exception {
        Path binDir = tmp.resolve("beta-bin");
        for (String platform : PackageBeta.PLATFORMS) {
            Files.writeString(Files.createDirectories(binDir.resolve(platform)).resolve("shipsmooth-beta"), platform);
        }
        PackageBeta.main(new String[]{"0.1.0-beta", binDir.toString(), tmp.resolve("out").toString()});
        assertTrue(Files.exists(tmp.resolve("out/shipsmooth-beta-0.1.0-beta-darwin-arm64.zip")));
        assertThrows(IllegalArgumentException.class, () -> PackageBeta.main(new String[]{"0.1.0-beta"}));
    }
}
