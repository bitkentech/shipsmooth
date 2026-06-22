package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch-table coverage for {@link ProjectDataStoreResolver#resolve} — one test per row of
 * the plan-85 branch table, asserting the returned {@link DataStoreResolution} variant.
 */
class ProjectDataStoreResolverTest {

    @TempDir Path repo;

    // ── Settled: matched external config entry whose dir exists ──────────────────

    @Test
    void configExternalDirExists_settledStandalone() throws IOException {
        Path stateDir = Files.createDirectories(repo.resolve("state"));
        Path config = writeConfig("""
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "%s"
                """.formatted(repo, stateDir));

        var r = resolve(config, Optional.of("https://github.com/org/repo.git"));
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r);
        var store = assertInstanceOf(ProjectDataStore.Standalone.class, settled.store());
        assertEquals(stateDir.toAbsolutePath().normalize(), store.stateRoot());
    }

    @Test
    void noRemote_matchesOnLocalPathAlone() throws IOException {
        Path stateDir = Files.createDirectories(repo.resolve("state"));
        Path config = writeConfig("""
                [[projects]]
                localPath = "%s"
                stateDir  = "%s"
                """.formatted(repo, stateDir));

        var r = resolve(config, Optional.empty());
        assertInstanceOf(DataStoreResolution.Settled.class, r);
    }

    @Test
    void firstMatchingEntryWins() throws IOException {
        Path state1 = Files.createDirectories(repo.resolve("state1"));
        Path state2 = Files.createDirectories(repo.resolve("state2"));
        Path config = writeConfig("""
                [[projects]]
                localPath = "%s"
                stateDir  = "%s"

                [[projects]]
                localPath = "%s"
                stateDir  = "%s"
                """.formatted(repo, state1, repo, state2));

        var r = resolve(config, Optional.empty());
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r);
        assertEquals(state1.toAbsolutePath().normalize(),
                ((ProjectDataStore.Standalone) settled.store()).stateRoot());
    }

    // ── Settled: in-repo .shipsmooth/plans present, no matching config ───────────

    @Test
    void inRepoShipsmoothPresent_noConfig_settledInRepo() throws IOException {
        Files.createDirectories(repo.resolve(".shipsmooth").resolve("plans"));
        Path absent = repo.resolve("shipsmooth.toml");

        var r = resolve(absent, Optional.empty());
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r);
        assertInstanceOf(ProjectDataStore.InRepo.class, settled.store());
    }

    @Test
    void bothInRepoAndConfiguredExternal_configWins() throws IOException {
        Files.createDirectories(repo.resolve(".shipsmooth").resolve("plans"));
        Path stateDir = Files.createDirectories(repo.resolve("state"));
        Path config = writeConfig("""
                [[projects]]
                localPath = "%s"
                stateDir  = "%s"
                """.formatted(repo, stateDir));

        var r = resolve(config, Optional.empty());
        var settled = assertInstanceOf(DataStoreResolution.Settled.class, r);
        assertInstanceOf(ProjectDataStore.Standalone.class, settled.store());
    }

    // ── NeedsDecision ────────────────────────────────────────────────────────────

    @Test
    void cleanFirstRun_needsDecisionExternalRecommended() {
        Path absent = repo.resolve("shipsmooth.toml");

        var r = resolve(absent, Optional.empty());
        var needs = assertInstanceOf(DataStoreResolution.NeedsDecision.class, r);
        assertEquals(DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN, needs.situation());
        assertEquals(DataStoreResolution.Choice.EXTERNAL, needs.recommended().choice());
        // in-repo is offered too, but not recommended
        assertTrue(needs.options().stream()
                .anyMatch(o -> o.choice() == DataStoreResolution.Choice.IN_REPO && !o.recommended()));
    }

    @Test
    void noMatchingEntry_cleanRepo_needsDecision() throws IOException {
        Path config = writeConfig("""
                [[projects]]
                localPath = "/some/other/path"
                stateDir  = "/state"
                """);

        var r = resolve(config, Optional.empty());
        assertInstanceOf(DataStoreResolution.NeedsDecision.class, r);
    }

    @Test
    void remoteUrlMismatch_treatedAsNoMatch() throws IOException {
        Path config = writeConfig("""
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "/state"
                """.formatted(repo));

        var r = resolve(config, Optional.of("https://github.com/org/OTHER.git"));
        assertInstanceOf(DataStoreResolution.NeedsDecision.class, r);
    }

    @Test
    void configExternalDirMissing_needsDecisionRecreate() throws IOException {
        Path stateDir = repo.resolve("gone"); // never created
        Path config = writeConfig("""
                [[projects]]
                localPath = "%s"
                stateDir  = "%s"
                """.formatted(repo, stateDir));

        var r = resolve(config, Optional.empty());
        var needs = assertInstanceOf(DataStoreResolution.NeedsDecision.class, r);
        assertEquals(DataStoreResolution.UndecidableSituation.CONFIG_DIR_MISSING, needs.situation());
        assertEquals(DataStoreResolution.Choice.RECREATE_MISSING_DIR, needs.recommended().choice());
        assertEquals(stateDir.toAbsolutePath().normalize(), needs.recommended().proposedPath());
    }

    // ── Unresolvable ──────────────────────────────────────────────────────────────

    @Test
    void legacyAgentsTree_unresolvable() throws IOException {
        Files.createDirectories(repo.resolve(".agents").resolve("plans"));
        Path absent = repo.resolve("shipsmooth.toml");

        var r = resolve(absent, Optional.empty());
        var bad = assertInstanceOf(DataStoreResolution.Unresolvable.class, r);
        assertEquals(DataStoreResolution.UnresolvableReason.LEGACY_AGENTS_TREE, bad.reason());
        // message (sourced from the reason) names both folders so the user can rename by hand
        assertTrue(bad.message().contains(".agents") && bad.message().contains(".shipsmooth"));
        assertTrue(bad.cause().isEmpty(), "an anticipated reason carries no throwable cause");
    }

    @Test
    void matchedEntryWithoutStateDir_unresolvableMalformed() throws IOException {
        Path config = writeConfig("""
                [[projects]]
                localPath = "%s"
                """.formatted(repo));

        var r = resolve(config, Optional.empty());
        var bad = assertInstanceOf(DataStoreResolution.Unresolvable.class, r);
        assertEquals(DataStoreResolution.UnresolvableReason.MALFORMED_CONFIG_ENTRY, bad.reason());
    }

    @Test
    void unparseableConfig_unresolvableUnknownWithCause() throws IOException {
        Path config = writeConfig("this is = = not valid toml [[[");

        var r = resolve(config, Optional.empty());
        var bad = assertInstanceOf(DataStoreResolution.Unresolvable.class, r);
        assertEquals(DataStoreResolution.UnresolvableReason.UNKNOWN, bad.reason());
        assertTrue(bad.cause().isPresent(), "UNKNOWN must retain the underlying cause");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private DataStoreResolution resolve(Path configFile, Optional<String> remoteUrl) {
        return new ProjectDataStoreResolver(() -> configFile).resolve(repo, remoteUrl);
    }

    private Path writeConfig(String toml) throws IOException {
        Path f = repo.resolve("shipsmooth.toml");
        Files.writeString(f, toml);
        return f;
    }
}
