package io.bitken.ss.cli.conf.ds;

import io.bitken.ss.Build;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the {@code manifest.toml} owned-folder marker (PB-360). */
class ManifestTest {

    @TempDir Path tmp;

    @Test
    void currentBodyHasTheExpectedShapeAndTheBuildVersion() {
        assertEquals(
                "[shipsmooth]\n"
                        + "kind = 'state-store'\n"
                        + "cli-version = '" + Build.VERSION + "'\n"
                        + "\n"
                        + "[manifest-schema]\n"
                        + "version = '1'\n"
                        + "\n",
                Manifest.currentBody());
    }

    @Test
    void writeThenReadRoundTripsAndClassifiesAsAStateStore() throws IOException {
        Path path = tmp.resolve("sub").resolve("manifest.toml");

        Manifest.write(path);

        Manifest read = Manifest.read(path).orElseThrow(
                () -> new AssertionError("freshly written manifest must parse"));
        assertTrue(read.isStateStore());
        assertEquals(Build.VERSION, read.getShipsmooth().getCliVersion());
        assertEquals("1", read.getManifestSchema().getVersion());
    }

    @Test
    void writeIsIdempotentAndLeavesNoTempLitter() throws IOException {
        Path path = tmp.resolve("manifest.toml");

        Manifest.write(path);
        Manifest.write(path);

        assertEquals(Manifest.currentBody(), Files.readString(path));
        try (var entries = Files.list(tmp)) {
            assertEquals(List.of("manifest.toml"),
                    entries.map(p -> p.getFileName().toString()).toList(),
                    "no temp file left behind");
        }
    }

    @Test
    void aMissingOrUnparseableMarkerReadsAsEmpty() throws IOException {
        Path path = tmp.resolve("manifest.toml");
        assertTrue(Manifest.read(path).isEmpty(), "missing file");

        Files.writeString(path, "this is not toml =");
        assertTrue(Manifest.read(path).isEmpty(), "unparseable file");
    }

    @Test
    void aMarkerWithAnUnknownKindStillParsesButIsNotAStateStore() throws IOException {
        Path path = tmp.resolve("manifest.toml");
        Files.writeString(path, "[shipsmooth]\nkind = 'something-else'\n");

        Manifest read = Manifest.read(path).orElseThrow();
        assertFalse(read.isStateStore());
    }

    @Test
    void aForeignTomlFileReadsAsEmpty() throws IOException {
        // An unrelated TOML file that happens to sit at manifest.toml: its
        // unknown tables make it "no usable marker", matching the Rust twin's
        // deny_unknown_fields behaviour.
        Path path = tmp.resolve("manifest.toml");
        Files.writeString(path, "[other]\nx = 1\n");

        assertTrue(Manifest.read(path).isEmpty(), "foreign toml -> no usable marker");
    }
}
