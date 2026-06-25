package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlschema.TomlSchema;
import org.tomlschema.ValidationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates every {@link StandaloneConfig} variant the emitter can produce against
 * {@code shipsmooth.tosd}. Each variant goes through {@link ArrayOfTablesTomlEmitter}
 * and is verified via the TOML Schema reference implementation.
 */
class SchemaConformanceTest {

    private static final Path SCHEMA = Path.of("src/test/resources/shipsmooth.tosd");

    @TempDir Path tmp;

    private void assertValid(StandaloneConfig config, String label) throws IOException {
        String toml = new ArrayOfTablesTomlEmitter().emit(config);
        Path file = tmp.resolve(label.replace(' ', '_') + ".toml");
        Files.writeString(file, toml);

        ValidationResult result = TomlSchema.load(SCHEMA).validate(file);
        assertTrue(result.isValid(), label + " must validate:\n" + toml + "\nErrors: " + result.errors());
    }

    @Test
    void inRepoEntry() throws IOException {
        StandaloneConfig config = new StandaloneConfig();
        StandaloneConfig.ProjectEntry entry = new StandaloneConfig.ProjectEntry();
        entry.setLocalPath("/home/user/repo");
        entry.setMode("in-repo");
        config.setProjects(List.of(entry));
        assertValid(config, "in-repo entry");
    }

    @Test
    void externalEntry() throws IOException {
        StandaloneConfig config = new StandaloneConfig();
        StandaloneConfig.ProjectEntry entry = new StandaloneConfig.ProjectEntry();
        entry.setLocalPath("/home/user/repo");
        entry.setRemoteUrl("git@github.com:user/repo.git");
        entry.setStateDir("/home/user/shipsmooth-state");
        entry.setMode("external");
        config.setProjects(List.of(entry));
        assertValid(config, "external entry");
    }

    @Test
    void backCompatEntry() throws IOException {
        StandaloneConfig config = new StandaloneConfig();
        StandaloneConfig.ProjectEntry entry = new StandaloneConfig.ProjectEntry();
        entry.setLocalPath("/home/user/repo");
        entry.setStateDir("/home/user/shipsmooth-state");
        // No mode — back-compat with old external-only entries.
        config.setProjects(List.of(entry));
        assertValid(config, "back-compat entry (no mode)");
    }

    @Test
    void minimalEntry() throws IOException {
        StandaloneConfig config = new StandaloneConfig();
        StandaloneConfig.ProjectEntry entry = new StandaloneConfig.ProjectEntry();
        entry.setLocalPath("/home/user/repo");
        // Only localPath — no remoteUrl, stateDir, or mode.
        config.setProjects(List.of(entry));
        assertValid(config, "minimal entry (localPath only)");
    }

    @Test
    void emptyConfigIsInvalid() throws IOException {
        StandaloneConfig config = new StandaloneConfig();
        config.setProjects(List.of());
        String toml = new ArrayOfTablesTomlEmitter().emit(config);
        Path file = tmp.resolve("empty.toml");
        Files.writeString(file, toml);

        ValidationResult result = TomlSchema.load(SCHEMA).validate(file);
        assertFalse(result.isValid(), "empty config (zero projects) must be rejected by the schema");
    }
}
