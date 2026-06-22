package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 preamble integration test for plan-85 Task 1 (legacy {@code .agents/} guard).
 *
 * <p>End-to-end behaviour: a project repo that still carries a legacy {@code .agents/}
 * shipsmooth data tree, with no config entry, must NOT be silently resolved as a clean
 * in-repo project (which would strand the user's existing plan history under a name the
 * new code never looks at). Instead the CLI must fail loudly with an actionable message
 * telling the user to rename {@code .agents/} → {@code .shipsmooth/} by hand.
 *
 * <p>Written before implementation and expected to be RED until the guard exists: today
 * {@link ProjectDataStoreResolver#resolve} returns {@link ProjectDataStore.InRepo}
 * silently regardless of any legacy {@code .agents/} tree.
 */
class LegacyAgentsFolderGuardIntegrationTest {

    @TempDir Path repoRoot;

    @Test
    void legacyAgentsDataTree_failsLoudlyInsteadOfSilentInRepo() throws IOException {
        // Simulate an existing user: a populated legacy .agents/plans/ tree, no config.
        Path legacyPlans = repoRoot.resolve(".agents").resolve("plans");
        Files.createDirectories(legacyPlans);
        Files.writeString(legacyPlans.resolve("plan-3.md"), "# pre-existing plan history\n");

        Path absentConfig = repoRoot.resolve("ss-config.toml"); // no config file present
        var resolver = new ProjectDataStoreResolver(() -> absentConfig);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> resolver.resolve(repoRoot, Optional.empty()),
                "a legacy .agents/ data tree must trigger a loud failure, not a silent InRepo");

        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(msg.contains(".agents") && msg.contains(".shipsmooth"),
                "guard message must name both the legacy and the new folder so the user "
                        + "knows to rename by hand; was: " + msg);
    }

    @Test
    void cleanRepoWithoutLegacyTree_stillResolvesInRepo() {
        // Control: no legacy tree, no config → ordinary in-repo resolution, no guard.
        Path absentConfig = repoRoot.resolve("ss-config.toml");
        var result = new ProjectDataStoreResolver(() -> absentConfig)
                .resolve(repoRoot, Optional.empty());
        assertInstanceOf(ProjectDataStore.InRepo.class, result);
    }
}
