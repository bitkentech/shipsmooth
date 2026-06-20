package io.bitken.ss;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for "separate repo" mode at the {@link ShipsmoothDataLocator}
 * seam: in separate-repo mode shipsmooth's data (plan files) lives under a
 * distinct <b>state root</b>, leaving the project working tree free of any
 * {@code .agents/} trace.
 *
 * <p>The default (single-root) behavior is covered by
 * {@link ShipsmoothDataLocatorIntegrationTest}; these tests must not change that.
 */
public class SeparateRepoModeIntegrationTest {

    @TempDir
    Path projectRoot;

    @TempDir
    Path stateRoot;

    @Test
    public void planFilesLiveUnderStateRootLeavingProjectTreeUntouched() {
        // In separate-repo mode the locator is given both roots: the project repo
        // (for git ops) and a distinct state root that owns the data tree.
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(projectRoot, stateRoot);

        // Plan files must resolve under the STATE root...
        assertTrue(locator.planTasksFile(82).toPath().startsWith(stateRoot),
                "plan task XML must live under the state root, not the project root");
        assertTrue(locator.planMarkdownFile(82).toPath().startsWith(stateRoot),
                "plan markdown must live under the state root, not the project root");

        // ...and NOT under the project root.
        assertFalse(locator.planTasksFile(82).toPath().startsWith(projectRoot),
                "plan files must not live under the project root in separate-repo mode");
    }

    /**
     * The separate state root must flow through the wired Dagger container, not
     * just a hand-built locator: a component built with distinct roots must yield
     * a locator that resolves data under the state root. This is the integration
     * test for the @RepoRoot/@StateRoot DI wiring; it would catch a mis-qualified
     * binding that fed repoRoot to both params.
     */
    @Test
    public void separateStateRootFlowsThroughDiToLocator() {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(projectRoot, stateRoot, new ExperimentalMode(false)))
                .build();

        // The locator the container hands out must honor the distinct state root.
        ShipsmoothDataLocator locator = app.dataLocator();
        assertTrue(locator.planTasksFile(1).toPath().startsWith(stateRoot),
                "wired locator must place plan files under the state root");
        assertFalse(locator.planTasksFile(1).toPath().startsWith(projectRoot),
                "wired locator must not place plan files under the project root");
    }
}
