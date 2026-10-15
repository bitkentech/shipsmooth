package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every shipped plugin manifest names Bitken Technologies as its author. The Claude
 * template feeds the dev, prod, Windows and beta renders unchanged, so checking the
 * source covers all four.
 */
class PluginAuthorTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("repo.root"));

    @ParameterizedTest
    @ValueSource(strings = {
        "harness/claude/src/main/resources/claude-plugin/plugin.json",
        ".claude-plugin/plugin.json",
        "harness/codex/src/main/resources/codex-plugin/.codex-plugin/plugin.json",
        "harness/opencode/src/main/ts/manifest/package.json",
    })
    void manifestAuthorIsBitkenTechnologies(String manifest) throws IOException {
        String author = new ObjectMapper().readTree(REPO_ROOT.resolve(manifest).toFile())
            .path("author").path("name").asText();
        assertEquals("Bitken Technologies", author, manifest);
    }
}
