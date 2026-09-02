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
}
