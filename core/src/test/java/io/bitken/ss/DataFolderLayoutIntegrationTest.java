package io.bitken.ss;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase-2 preamble integration test for plan-85 (data folder naming &amp; layout).
 *
 * <p>Exercises the end-to-end contract that {@link ShipsmoothDataLocator} — the single
 * source of path truth — produces the new {@code .shipsmooth/} layout: in-repo mode puts
 * plans under {@code <repoRoot>/.shipsmooth/plans/}, and standalone mode puts them
 * directly under the dedicated {@code stateRoot} ({@code <stateDir>/plans/}) with no
 * dot-folder segment, because the dedicated dir <em>is</em> the data root.
 *
 * <p>Written before implementation (Tasks 2/3) and expected to be RED until
 * {@code stateRoot} owns the layout and the data-tree segment becomes {@code .shipsmooth}.
 */
public class DataFolderLayoutIntegrationTest {

    @TempDir Path repoRoot;
    @TempDir Path externalStateDir;

    @Test
    public void inRepoModePutsPlansUnderShipsmoothFolder() throws Exception {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(repoRoot))
                .build();

        ShipsmoothDataLocator locator = app.dataLocator();

        Path expected = repoRoot.resolve(".shipsmooth").resolve("plans").resolve("plan-7.md");
        assertEquals(expected.toFile().getCanonicalPath(),
                locator.planMarkdownFile(7).getCanonicalPath(),
                "in-repo plans must live under <repoRoot>/.shipsmooth/plans/, not .agents/");
    }

    @Test
    public void manifestFileSitsAtTheDataRootInBothModes() throws Exception {
        AppComponents inRepo = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(repoRoot))
                .build();
        assertEquals(
                repoRoot.resolve(".shipsmooth").resolve("manifest.toml").toFile().getCanonicalPath(),
                inRepo.dataLocator().manifestFile().toFile().getCanonicalPath(),
                "in-repo manifest must live at <repoRoot>/.shipsmooth/manifest.toml");

        Files.createDirectories(externalStateDir);
        AppComponents standalone = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(repoRoot, externalStateDir,
                        new io.bitken.ss.conf.ExperimentalMode(false)))
                .build();
        ShipsmoothDataLocator locator = standalone.dataLocator();
        assertEquals(
                externalStateDir.resolve("manifest.toml").toFile().getCanonicalPath(),
                locator.manifestFile().toFile().getCanonicalPath(),
                "standalone manifest must hang directly off the dedicated stateDir");
        assertEquals(
                locator.plansDir().getParent().toFile().getCanonicalPath(),
                locator.manifestFile().getParent().toFile().getCanonicalPath(),
                "marker and plans dir must share the data root");
    }

    @Test
    public void standaloneModePutsPlansDirectlyUnderStateDir() throws Exception {
        // Two-root (standalone) wiring: a dedicated state dir owns the data tree.
        Files.createDirectories(externalStateDir);

        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(repoRoot, externalStateDir,
                        new io.bitken.ss.conf.ExperimentalMode(false)))
                .build();

        ShipsmoothDataLocator locator = app.dataLocator();

        // No dot-folder segment in standalone: the dedicated dir is the root.
        Path expected = externalStateDir.resolve("plans").resolve("plan-7-tasks.xml");
        assertEquals(expected.toFile().getCanonicalPath(),
                locator.planTasksFile(7).getCanonicalPath(),
                "standalone plans must hang directly off the dedicated stateDir (no .shipsmooth/.agents segment)");
    }
}
