package io.bitken.ss.cli.store;

import io.bitken.ss.cli.conf.ds.ConfigWriter;
import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import io.bitken.ss.cli.conf.ds.ProjectDataStore;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code store init} — the guarded act-on-answer writer. Each test binds a
 * resolution (as {@code main} would) and runs the command against an isolated config file.
 */
class InitTest {

    @TempDir Path tmp;

    private Init boundInit(Path config, DataStoreResolution resolution, Path repoRoot) {
        Init init = new Init(new ProjectDataStoreResolver(() -> config), new ConfigWriter(() -> config));
        init.bind(repoRoot, Optional.empty(), resolution);
        return init;
    }

    private int run(Init init, String... args) {
        return new CommandLine(init.getSpec()).execute(args);
    }

    @Test
    void external_createsDirWritesConfigAndSettles() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path external = tmp.resolve("ext");

        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(DataStoreResolution.Choice.EXTERNAL, external, true),
                        new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, repo.resolve(".shipsmooth"), false)));

        int code = run(boundInit(config, needs, repo), "--choice", "external", "--path", external.toString());

        assertEquals(0, code);
        assertTrue(Files.isDirectory(external.resolve(".git")), "external state repo created");
        // config now resolves settled-standalone for this repo
        var r = new ProjectDataStoreResolver(() -> config).resolve(repo, Optional.empty());
        assertInstanceOf(ProjectDataStore.Standalone.class,
                ((DataStoreResolution.Settled) r).store());
    }

    @Test
    void inRepo_createsFolderWritesConfigAndSettles() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(DataStoreResolution.Choice.EXTERNAL, tmp.resolve("ext"), true),
                        new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, repo.resolve(".shipsmooth"), false)));

        int code = run(boundInit(config, needs, repo), "--choice", "in-repo");

        assertEquals(0, code);
        assertTrue(Files.isDirectory(repo.resolve(".shipsmooth").resolve("plans")), "in-repo folder created");
        var r = new ProjectDataStoreResolver(() -> config).resolve(repo, Optional.empty());
        assertInstanceOf(ProjectDataStore.InRepo.class, ((DataStoreResolution.Settled) r).store());
    }

    @Test
    void recreate_provisionsConfiguredDirWithoutChangingConfig() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path stateDir = tmp.resolve("gone");
        // Pre-existing external config pointing at the missing dir.
        new ConfigWriter(() -> config).writeExternal(repo, Optional.empty(), stateDir);

        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CONFIG_DIR_MISSING,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.RECREATE_MISSING_DIR, stateDir, true)));

        int code = run(boundInit(config, needs, repo), "--choice", "recreate", "--path", stateDir.toString());

        assertEquals(0, code);
        assertTrue(Files.isDirectory(stateDir.resolve(".git")), "configured dir recreated");
    }

    @Test
    void alreadySettled_refusesWithoutMutating() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        var settled = new DataStoreResolution.Settled(new ProjectDataStore.InRepo(repo));
        int code = run(boundInit(config, settled, repo), "--choice", "in-repo");

        assertNotEquals(0, code, "must refuse when already settled");
        assertFalse(Files.exists(config), "no config should be written");
    }

    @Test
    void unresolvable_refuses() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        var bad = DataStoreResolution.Unresolvable.of(
                DataStoreResolution.UnresolvableReason.LEGACY_AGENTS_TREE);
        int code = run(boundInit(config, bad, repo), "--choice", "external", "--path", tmp.resolve("x").toString());

        assertNotEquals(0, code, "must refuse when unresolvable");
    }

    @Test
    void offMenuChoice_refuses() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        // CONFIG_DIR_MISSING only offers RECREATE; choosing external is off-menu.
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CONFIG_DIR_MISSING,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.RECREATE_MISSING_DIR, tmp.resolve("d"), true)));

        int code = run(boundInit(config, needs, repo), "--choice", "external", "--path", tmp.resolve("x").toString());

        assertNotEquals(0, code, "must refuse an off-menu choice");
    }

    @Test
    void unknownChoiceToken_refuses() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, repo.resolve(".shipsmooth"), true)));

        int code = run(boundInit(config, needs, repo), "--choice", "sideways");
        assertNotEquals(0, code);
    }
}
