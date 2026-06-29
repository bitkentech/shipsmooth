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
 * End-to-end feature test for plan-93 (PB-365): the storage-vocabulary rename. The user's
 * {@code shipsmooth.toml} must be written in the new vocabulary — {@code storageType =
 * 'same-repo' | 'separate-dir'} with the separate-dir location under {@code storageRoot} — and
 * must round-trip back through the resolver to a settled resolution.
 *
 * <p>No back-compat: the writer emits and the resolver reads the new keys/values only (the
 * tool has no users carrying old-vocabulary config). The old {@code mode}/{@code in-repo}/
 * {@code external}/{@code stateDir} tokens must no longer appear on the write side.
 */
class StorageTypeVocabularyIntegrationTest {

    @TempDir Path tmp;

    @Test
    void writesNewVocabulary_storageTypeAndStorageRoot() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repoFs = Files.createDirectories(tmp.resolve("audio-gen"));
        Path storageFs = Files.createDirectories(tmp.resolve("audio-gen-shipsmooth"));
        Path repoEmbedded = Files.createDirectories(tmp.resolve("ss-toml"));

        ConfigWriter writer = new ConfigWriter(() -> config);
        writer.writeExternal(repoFs, Optional.of("git@github.com:pramodbiligiri/audio-gen.git"), storageFs);
        writer.writeInRepo(repoEmbedded, Optional.of("git@github.com:pramodbiligiri/ss-toml.git"));

        String written = Files.readString(config);
        List<String> lines = Files.readAllLines(config);

        // The separate-dir entry: storageType = 'separate-dir' + storageRoot pointing at its dir.
        assertTrue(lines.stream().anyMatch(l -> l.trim().equals("storageType = 'separate-dir'")),
                "separate-dir entry must write storageType = 'separate-dir':\n" + written);
        assertTrue(lines.stream().anyMatch(l -> l.trim().startsWith("storageRoot = ")
                        && l.contains(storageFs.toAbsolutePath().normalize().toString())),
                "separate-dir location must be written under storageRoot:\n" + written);

        // The same-repo entry: storageType = 'same-repo', no storageRoot.
        assertTrue(lines.stream().anyMatch(l -> l.trim().equals("storageType = 'same-repo'")),
                "same-repo entry must write storageType = 'same-repo':\n" + written);
        long storageRootLines = lines.stream().filter(l -> l.trim().startsWith("storageRoot = ")).count();
        assertEquals(1, storageRootLines,
                "same-repo entry must not emit a storageRoot key:\n" + written);

        // The old vocabulary must be gone from the write side entirely.
        assertFalse(written.contains("mode = "), "old 'mode' key must not be written:\n" + written);
        assertFalse(written.contains("stateDir = "), "old 'stateDir' key must not be written:\n" + written);
        assertFalse(written.contains("'in-repo'"), "old 'in-repo' value must not be written:\n" + written);
    }

    @Test
    void newVocabulary_roundTripsThroughResolver() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repoFs = Files.createDirectories(tmp.resolve("audio-gen"));
        Path storageFs = Files.createDirectories(tmp.resolve("audio-gen-shipsmooth"));

        new ConfigWriter(() -> config)
                .writeExternal(repoFs, Optional.of("git@github.com:pramodbiligiri/audio-gen.git"), storageFs);

        var r = new ProjectDataStoreResolver(() -> config).resolve(repoFs,
                Optional.of("git@github.com:pramodbiligiri/audio-gen.git"));
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r,
                "new-vocabulary config must read back through the resolver to settled");
        assertEquals(storageFs.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) settled.store()).stateRoot());
    }
}
