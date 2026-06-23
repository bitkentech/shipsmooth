package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioural tests for the {@link DataStoreResolution} model itself. */
class DataStoreResolutionTest {

    @Test
    void recommended_returnsTheMarkedOption() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(
                        new DataStoreResolution.Option(DataStoreResolution.Choice.EXTERNAL, Path.of("/ext"), true),
                        new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, Path.of("/in"), false)));

        assertEquals(DataStoreResolution.Choice.EXTERNAL, needs.recommended().choice());
    }

    @Test
    void recommended_throwsWhenNoOptionMarked() {
        var needs = new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.IN_REPO, Path.of("/in"), false)));

        assertThrows(IllegalStateException.class, needs::recommended);
    }

    @Test
    void unknownFactory_setsUnknownReasonAndRetainsCause() {
        var cause = new RuntimeException("boom");
        var bad = DataStoreResolution.Unresolvable.unknown(cause);

        assertEquals(DataStoreResolution.UnresolvableReason.UNKNOWN, bad.reason());
        assertEquals(Optional.of(cause), bad.cause());
        assertTrue(bad.message().equals(DataStoreResolution.UnresolvableReason.UNKNOWN.message()));
    }

    @Test
    void everySituationAndReasonHasANonBlankMessage() {
        for (var s : DataStoreResolution.UndecidableSituation.values()) {
            assertTrue(s.message() != null && !s.message().isBlank(), s.name());
        }
        for (var r : DataStoreResolution.UnresolvableReason.values()) {
            assertTrue(r.message() != null && !r.message().isBlank(), r.name());
        }
    }
}
