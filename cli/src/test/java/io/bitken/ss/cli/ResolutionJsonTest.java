package io.bitken.ss.cli;

import io.bitken.ss.cli.conf.ds.DataStoreResolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(json.contains("\"choice\":\"separate-dir\""), json);
        assertTrue(json.contains("\"choice\":\"same-repo\""), json);
        assertTrue(json.contains("\"recommended\":true"), json);
        assertTrue(json.contains("\"recommended\":false"), json);
    }

    @Test
    void needsDecision_emitsDisplayReadyPromptTheSkillShowsVerbatim() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(
                        new DataStoreResolution.Option(DataStoreResolution.Choice.EXTERNAL, Path.of("/ext"), true),
                        new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, Path.of("/in"), false)));

        String json = ResolutionJson.needsDecision(needs);

        // A single `prompt` field the skill renders verbatim: the question + each option
        // (human label, path) with the recommended one marked.
        assertTrue(json.contains("\"prompt\":\""), "prompt field present: " + json);
        assertTrue(json.contains("Where should shipsmooth store all its information"),
                "prompt carries the question: " + json);
        assertTrue(json.contains("Recommended"), json);
        assertTrue(json.contains("next to this repo"), json);
        assertTrue(json.contains("/ext"), json);
        assertTrue(json.contains("Alternative"), json);
        assertTrue(json.contains("inside this repo"), json);
        assertTrue(json.contains("/in"), json);
        // When a separate folder is offered, the prompt invites a custom path.
        assertTrue(json.contains("enter a different folder path"), json);
        // Multi-line prompt must keep the JSON line valid: real newlines are escaped.
        assertTrue(json.contains("\\n"), "embedded newlines must be escaped: " + json);
        assertFalse(json.contains("\n"), "the JSON must remain a single physical line: " + json);
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
