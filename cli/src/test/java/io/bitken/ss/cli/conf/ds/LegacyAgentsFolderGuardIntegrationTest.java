package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 preamble integration test for plan-85 Task 1 (legacy {@code .agents/} guard),
 * updated to the Task-4 branch-table contract.
 *
 * <p>End-to-end behaviour: a project repo that still carries a legacy {@code .agents/}
 * shipsmooth data tree, with no config entry, must NOT be silently resolved as a clean
 * in-repo project (which would strand the user's existing plan history under a name the
 * new code never looks at). {@code resolve()} surfaces this loudly as an
 * {@link DataStoreResolution.Unresolvable} with reason {@code LEGACY_AGENTS_TREE}, whose
 * message names both folders so the user knows to rename by hand.
 */
class LegacyAgentsFolderGuardIntegrationTest {

    @TempDir Path repoRoot;

    @Test
    void legacyAgentsDataTree_unresolvableInsteadOfSilentInRepo() throws IOException {
        // Simulate an existing user: a populated legacy .agents/plans/ tree, no config.
        Path legacyPlans = repoRoot.resolve(".agents").resolve("plans");
        Files.createDirectories(legacyPlans);
        Files.writeString(legacyPlans.resolve("plan-3.md"), "# pre-existing plan history\n");

        Path absentConfig = repoRoot.resolve("shipsmooth.toml"); // no config file present
        var result = new ProjectDataStoreResolver(() -> absentConfig)
                .resolve(repoRoot, Optional.empty());

        var bad = assertInstanceOf(DataStoreResolution.Unresolvable.class, result,
                "a legacy .agents/ data tree must be Unresolvable, not a silent in-repo Settled");
        assertEquals(DataStoreResolution.UnresolvableReason.LEGACY_AGENTS_TREE, bad.reason());
        assertTrue(bad.message().contains(".agents") && bad.message().contains(".shipsmooth"),
                "message must name both the legacy and the new folder; was: " + bad.message());
    }

    @Test
    void cleanRepoWithoutLegacyTree_doesNotTripGuard() {
        // Control: no legacy tree, no config, no state → a clean first run (NOT Unresolvable).
        Path absentConfig = repoRoot.resolve("shipsmooth.toml");
        var result = new ProjectDataStoreResolver(() -> absentConfig)
                .resolve(repoRoot, Optional.empty());

        var needs = assertInstanceOf(DataStoreResolution.NeedsDecision.class, result,
                "a clean repo must reach the first-run decision, not the legacy guard");
        assertEquals(DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN, needs.situation());
    }
}
