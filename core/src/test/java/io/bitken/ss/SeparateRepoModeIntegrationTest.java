package io.bitken.ss;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.ledger.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-2 preamble integration tests for plan-82 "separate repo" mode.
 *
 * <p>These exercise the feature end-to-end at the {@link ShipsmoothDataLocator}
 * seam: in separate-repo mode all shipsmooth <em>data</em> (plan files, ledger,
 * object store) lives under a distinct <b>state root</b>, leaving the project
 * working tree free of any {@code .agents/} trace, while git <b>worktrees</b>
 * are parked <em>outside</em> the project tree yet stay attached to the project
 * repo's git.
 *
 * <p>They are committed RED: the two-root {@code ShipsmoothDataLocator} API they
 * target does not exist yet (the locator currently knows only a single
 * {@code repoRoot}). They define the contract Task 5/6 must satisfy. The default
 * (single-root) behavior is covered by {@link ShipsmoothDataLocatorIntegrationTest};
 * these tests must not change that.
 */
public class SeparateRepoModeIntegrationTest {

    @TempDir
    Path projectRoot;

    @TempDir
    Path stateRoot;

    @Test
    public void dataLivesUnderStateRootLeavingProjectTreeUntouched() throws Exception {
        // In separate-repo mode the locator is given both roots: the project repo
        // (for git ops / worktree attachment) and a distinct state root that owns
        // the data tree.
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(projectRoot, stateRoot);

        // Plan files, ledger and object store must resolve under the STATE root...
        assertTrue(locator.planTasksFile(82).toPath().startsWith(stateRoot),
                "plan task XML must live under the state root, not the project root");
        assertTrue(locator.ledgerPath().startsWith(stateRoot),
                "ledger must live under the state root");
        assertTrue(locator.objectStorePath().startsWith(stateRoot),
                "object store must live under the state root");

        // ...and NOT under the project root.
        assertFalse(locator.ledgerPath().startsWith(projectRoot),
                "ledger must not live under the project root in separate-repo mode");

        // Bootstrapping creates state under the state root and adds zero .agents
        // trace to the project working tree (the whole point of the mode).
        locator.bootstrap();
        assertTrue(Files.exists(locator.objectStorePath()), "object store should be created under state root");
        assertFalse(Files.exists(projectRoot.resolve(".agents")),
                "project working tree must remain free of any .agents trace");
    }

    @Test
    public void worktreesAreParkedOutsideProjectTree() {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(projectRoot, stateRoot);

        // Worktree locations must resolve to an absolute path OUTSIDE the project
        // tree (so nothing appears inside it), even though the worktree stays a
        // worktree of the project repo's git.
        Path taskWorktree = locator.worktreeBase("task-1");
        Path integrationWorktree = locator.integrationBase(82);

        assertFalse(taskWorktree.toAbsolutePath().startsWith(projectRoot.toAbsolutePath()),
                "task worktree must be parked outside the project tree");
        assertFalse(integrationWorktree.toAbsolutePath().startsWith(projectRoot.toAbsolutePath()),
                "integration worktree must be parked outside the project tree");
    }

    /**
     * The separate state root must flow through the wired Dagger container, not
     * just a hand-built locator: a component built with distinct roots must yield
     * a locator — and a locator-consuming service (EventLedger) — that reads and
     * writes under the state root, leaving the project tree untouched. This is
     * the integration test for the @RepoRoot/@StateRoot DI wiring; it would catch
     * a mis-qualified binding that fed repoRoot to both params.
     */
    @Test
    public void separateStateRootFlowsThroughDiToConsumingServices() throws Exception {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(projectRoot, stateRoot, new ExperimentalMode(false)))
                .build();

        // The locator the container hands out must honor the distinct state root.
        ShipsmoothDataLocator locator = app.dataLocator();
        assertTrue(locator.ledgerPath().startsWith(stateRoot),
                "wired locator must place the ledger under the state root");
        assertFalse(locator.ledgerPath().startsWith(projectRoot),
                "wired locator must not place the ledger under the project root");

        // A real consuming service resolved from the same graph must physically
        // write under the state root, with zero trace left in the project tree.
        EventLedger ledger = app.eventLedger();
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.STATUS_UPDATED, "1", null, "status=de-risked", null));

        assertTrue(Files.exists(stateRoot.resolve(".agents/ledger.jsonl")),
                "ledger file must be written under the state root");
        assertFalse(Files.exists(projectRoot.resolve(".agents")),
                "project working tree must remain free of any .agents trace");
    }
}
