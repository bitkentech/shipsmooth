package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolutionJsonTest {

    @Test
    void needsDecision_emitsStatusSituationAndOptionTokens() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(
                        new DataStoreResolution.Option(DataStoreResolution.Choice.EXTERNAL, Path.of("/ext"), true),
                        new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, Path.of("/in"), false)));

        String json = ResolutionJson.needsDecision(needs);
        assertTrue(json.contains("\"status\":\"needs-decision\""), json);
        assertTrue(json.contains("\"situation\":\"clean-first-run\""), json);
        assertTrue(json.contains("\"choice\":\"external\""), json);
        assertTrue(json.contains("\"choice\":\"in-repo\""), json);
        assertTrue(json.contains("\"recommended\":true"), json);
        assertTrue(json.contains("\"recommended\":false"), json);
    }

    @Test
    void unresolvable_emitsStatusReasonAndMessage() {
        var bad = DataStoreResolution.Unresolvable.of(
                DataStoreResolution.UnresolvableReason.LEGACY_AGENTS_TREE);

        String json = ResolutionJson.unresolvable(bad);
        assertTrue(json.contains("\"status\":\"unresolvable\""), json);
        assertTrue(json.contains("\"reason\":\"LEGACY_AGENTS_TREE\""), json);
        assertTrue(json.contains(".shipsmooth"), json);
    }

    @Test
    void recreateAndInRepoNotSetUpTokens() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CONFIG_DIR_MISSING,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.RECREATE_MISSING_DIR, Path.of("/d"), true)));
        assertTrue(ResolutionJson.needsDecision(needs).contains("\"choice\":\"recreate\""));

        var inRepo = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.IN_REPO_NOT_SET_UP,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.IN_REPO, Path.of("/in"), true)));
        assertTrue(ResolutionJson.needsDecision(inRepo).contains("\"situation\":\"in-repo-not-set-up\""));
    }

    @Test
    void messageWithQuotesIsEscaped() {
        // A proposed path containing a quote must not break the JSON string.
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.EXTERNAL, Path.of("/weird\"path"), true)));
        assertTrue(ResolutionJson.needsDecision(needs).contains("\\\""), "quote must be escaped");
    }
}
