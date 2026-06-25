package io.bitken.ss.cli.conf.ds;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: a {@link ConfigWriter} upsert must be re-readable by
 * {@link ProjectDataStoreResolver}, and upserts must be idempotent on (localPath, remoteUrl).
 */
class ConfigWriterTest {

    @TempDir Path tmp;

    @Test
    void writeExternal_thenResolverReadsStandalone() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path stateDir = Files.createDirectories(tmp.resolve("state"));

        new ConfigWriter(() -> config).writeExternal(repo, Optional.empty(), stateDir);

        assertTrue(Files.exists(config), "config file must be created");
        var r = new ProjectDataStoreResolver(() -> config).resolve(repo, Optional.empty());
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r);
        assertEquals(stateDir.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) settled.store()).stateRoot());
    }

    @Test
    void emitsInjectedSchemaLocation() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        String loc = "https://example.test/shipsmooth.tosd";
        new ConfigWriter(() -> config, loc).writeInRepo(repo, Optional.empty());

        String written = Files.readString(config);
        assertTrue(written.contains("[toml-schema]"), written);
        assertTrue(written.contains("location = '" + loc + "'"), written);
    }

    @Test
    void omitsLocationWhenNotInjected() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        // Null schema location → [toml-schema] carries version only, no location key.
        new ConfigWriter(() -> config, null).writeInRepo(repo, Optional.empty());

        String written = Files.readString(config);
        assertTrue(written.contains("[toml-schema]"), written);
        assertTrue(written.contains("version = "), written);
        assertFalse(written.contains("location"), "no location key when none injected:\n" + written);
    }

    @Test
    void writeInRepo_thenResolverSeesInRepoEntry() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        new ConfigWriter(() -> config).writeInRepo(repo, Optional.empty());

        // No .shipsmooth/plans yet → an in-repo entry is recognised but not yet set up.
        var r = new ProjectDataStoreResolver(() -> config).resolve(repo, Optional.empty());
        var needs = assertInstanceOf(DataStoreResolution.NeedsDecision.class, r);
        assertEquals(DataStoreResolution.UndecidableSituation.IN_REPO_NOT_SET_UP, needs.situation());

        // Once the folder exists, the same entry resolves settled in-repo.
        Files.createDirectories(repo.resolve(".shipsmooth").resolve("plans"));
        var r2 = new ProjectDataStoreResolver(() -> config).resolve(repo, Optional.empty());
        assertInstanceOf(ProjectDataStore.InRepo.class,
                ((DataStoreResolution.Settled) r2).store());
    }

    @Test
    void upsertIsIdempotent_replacesMatchingEntry() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path stateA = Files.createDirectories(tmp.resolve("stateA"));
        Path stateB = Files.createDirectories(tmp.resolve("stateB"));

        ConfigWriter writer = new ConfigWriter(() -> config);
        writer.writeExternal(repo, Optional.empty(), stateA);
        writer.writeExternal(repo, Optional.empty(), stateB); // same project, new dir

        var settled = (DataStoreResolution.Settled)
                new ProjectDataStoreResolver(() -> config).resolve(repo, Optional.empty());
        assertEquals(stateB.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) settled.store()).stateRoot(),
                "second upsert for the same project must replace, not duplicate");
    }

    @Test
    void distinctProjectsCoexist() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo1 = Files.createDirectories(tmp.resolve("repo1"));
        Path repo2 = Files.createDirectories(tmp.resolve("repo2"));
        Path state1 = Files.createDirectories(tmp.resolve("s1"));
        Path state2 = Files.createDirectories(tmp.resolve("s2"));

        ConfigWriter writer = new ConfigWriter(() -> config);
        writer.writeExternal(repo1, Optional.empty(), state1);
        writer.writeExternal(repo2, Optional.empty(), state2);

        var resolver = new ProjectDataStoreResolver(() -> config);
        assertEquals(state1.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) ((DataStoreResolution.Settled)
                        resolver.resolve(repo1, Optional.empty())).store()).stateRoot());
        assertEquals(state2.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) ((DataStoreResolution.Settled)
                        resolver.resolve(repo2, Optional.empty())).store()).stateRoot());
    }

    // ── Atomic write: a failed serialize never truncates the config (plan-87) ──────

    @Test
    void failedSerialize_leavesExistingConfigIntactAndNoTempLitter() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path good = Files.createDirectories(tmp.resolve("good"));

        // First write a valid config the normal way.
        new ConfigWriter(() -> config).writeExternal(repo, Optional.empty(), good);
        String before = Files.readString(config);
        assertFalse(before.isBlank(), "precondition: a valid non-empty config exists");

        // Now attempt a write whose serialize blows up (mirrors the JPMS reflection failure,
        // now on the hand-rolled emitter that replaced the Jackson write path in plan-90).
        ArrayOfTablesTomlEmitter exploding = new ArrayOfTablesTomlEmitter() {
            @Override
            String emit(StandaloneConfig cfg) {
                throw new RuntimeException("boom");
            }
        };
        Path repo2 = Files.createDirectories(tmp.resolve("repo2"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        assertThrows(RuntimeException.class,
                () -> new ConfigWriter(() -> config, SchemaConfig.SCHEMA_LOCATION, new TomlMapper(), exploding)
                        .writeExternal(repo2, Optional.empty(), other));

        // The original config must survive byte-for-byte — never a truncated 0-byte file.
        assertEquals(before, Files.readString(config),
                "a failed write must not corrupt the existing config");
        // And no temp file may be left behind in the config directory.
        try (var listing = Files.list(config.getParent())) {
            List<Path> tmps = listing.filter(p -> p.getFileName().toString().contains(".tmp")).toList();
            assertTrue(tmps.isEmpty(), "failed write left temp litter: " + tmps);
        }
    }
}
