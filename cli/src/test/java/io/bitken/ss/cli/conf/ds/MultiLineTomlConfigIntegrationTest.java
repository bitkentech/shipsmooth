package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end feature test for plan-90: the user's {@code shipsmooth.toml} must be
 * written as multi-line TOML array-of-tables ({@code [[projects]]} blocks), never as
 * a single inline {@code projects = [{...}]} line — while still round-tripping through
 * the (unchanged, Jackson-based) resolver read path.
 */
class MultiLineTomlConfigIntegrationTest {

    @TempDir Path tmp;

    @Test
    void writesArrayOfTablesBlocks_notSingleInlineLine() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repoExt = Files.createDirectories(tmp.resolve("audio-gen"));
        Path stateExt = Files.createDirectories(tmp.resolve("audio-gen-shipsmooth"));
        Path repoInRepo = Files.createDirectories(tmp.resolve("ss-toml"));

        ConfigWriter writer = new ConfigWriter(() -> config);
        writer.writeExternal(repoExt, Optional.of("git@github.com:pramodbiligiri/audio-gen.git"), stateExt);
        writer.writeInRepo(repoInRepo, Optional.of("git@github.com:pramodbiligiri/ss-toml.git"));

        List<String> lines = Files.readAllLines(config);

        // Each project is its own [[projects]] block — two projects, two headers.
        long headers = lines.stream().filter(l -> l.trim().equals("[[projects]]")).count();
        assertEquals(2, headers,
                "expected one [[projects]] header per project; file was:\n" + Files.readString(config));

        // No single-line inline-array collapse: the whole array must not live on one line.
        assertFalse(lines.stream().anyMatch(l -> l.contains("projects = [{")),
                "config collapsed to a single inline-array line:\n" + Files.readString(config));

        // The filesystem entry's storageRoot appears on its own key line (multi-line, one key per line).
        assertTrue(lines.stream().anyMatch(l -> l.trim().startsWith("storageRoot = ")
                        && l.contains(stateExt.toAbsolutePath().normalize().toString())),
                "storageRoot not emitted on its own key line:\n" + Files.readString(config));

        // The embedded entry omits storageRoot entirely (exactly one storageRoot line across the file).
        long storageRootLines = lines.stream().filter(l -> l.trim().startsWith("storageRoot = ")).count();
        assertEquals(1, storageRootLines,
                "embedded entry must not emit a storageRoot key:\n" + Files.readString(config));
    }

    @Test
    void multiLineOutput_roundTripsThroughResolver() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repoExt = Files.createDirectories(tmp.resolve("audio-gen"));
        Path stateExt = Files.createDirectories(tmp.resolve("audio-gen-shipsmooth"));

        new ConfigWriter(() -> config)
                .writeExternal(repoExt, Optional.of("git@github.com:pramodbiligiri/audio-gen.git"), stateExt);

        var r = new ProjectDataStoreResolver(() -> config).resolve(repoExt,
                Optional.of("git@github.com:pramodbiligiri/audio-gen.git"));
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r,
                "multi-line [[projects]] output must read back through the resolver");
        assertEquals(stateExt.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) settled.store()).stateRoot());
    }
}
