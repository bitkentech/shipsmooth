package io.bitken.ss.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResolveVersionsTest {

    private static String fixture() throws IOException {
        try (var in = ResolveVersionsTest.class.getResourceAsStream("/npm-registry-claude-code.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsStableDistTag() throws IOException {
        assertEquals("2.1.236", ResolveVersions.claudeCodeStable(fixture()));
    }

    @Test
    void stableIsNotLatest() throws IOException {
        // Guards against someone "simplifying" stable -> latest; they differ upstream.
        assertEquals("2.1.252", ResolveVersions.distTag(fixture(), "latest"));
        assertEquals("2.1.236", ResolveVersions.distTag(fixture(), "stable"));
    }

    @Test
    void missingDistTagThrows() {
        String json = "{\"dist-tags\":{\"latest\":\"1.0.0\"}}";
        assertThrows(IllegalStateException.class, () -> ResolveVersions.distTag(json, "stable"));
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(IOException.class, () -> ResolveVersions.claudeCodeStable("not json"));
    }

    @Test
    void mainRejectsAMissingShipsmoothVersion() {
        // The :docker:resolveVersions task feeds plugin.version as arg 0. If that
        // wiring ever regresses to nothing, the build must fail here, not resolve
        // a blank version and bake an empty io.bitken.ss.shipsmooth.version label.
        // (Guarded: only meaningful when -Dshipsmooth.version is unset, as in CI/dev.)
        if (!System.getProperty("shipsmooth.version", "").isBlank()) {
            return;
        }
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> ResolveVersions.main(new String[] {}));
        assertEquals("shipsmooth version required (arg 0 or -Dshipsmooth.version)", e.getMessage());
    }

    @Test
    void resolvePairsClaudeCodeWithExactlyTheGivenShipsmoothVersion() throws Exception {
        // No transformation, no default — the compound tag is claude-<x>-ss-<y>
        // with y verbatim. Pairs with the Gradle-level check that y == plugin.version.
        ResolveVersions.Versions v = new ResolveVersions.Versions("2.1.236", "0.3.36-rc1");
        assertEquals("0.3.36-rc1", v.shipsmooth());
        assertEquals("claude-2.1.236-ss-0.3.36-rc1", v.compoundTag());
    }
}
