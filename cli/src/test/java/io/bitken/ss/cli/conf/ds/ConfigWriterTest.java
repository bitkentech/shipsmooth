package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
