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

    private static final Path SCHEMA = SchemaResource.schemaPath();

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
    void emptyConfig() throws IOException {
        StandaloneConfig config = new StandaloneConfig();
        // Zero projects — valid initial state before any project is added.
        config.setProjects(List.of());
        assertValid(config, "empty config (zero projects)");
    }

    @Test
    void schemaTableWithLocation() throws IOException {
        // The full [toml-schema] table (version + location) as ConfigWriter bakes it.
        StandaloneConfig config = new StandaloneConfig();
        StandaloneConfig.TomlSchemaRef ref = new StandaloneConfig.TomlSchemaRef();
        ref.setVersion("1.0.0");
        ref.setLocation("https://raw.githubusercontent.com/bitkentech/shipsmooth/v0.3.29/dist/schemas/shipsmooth.tosd");
        config.setTomlSchema(ref);
        config.setProjects(List.of());
        assertValid(config, "[toml-schema] with location");
    }

    @Test
    void schemaTableVersionOnly() throws IOException {
        // No location injected — the emitted table carries version only. Spec-valid:
        // location is optional under [toml-schema]. (plan-91 Task 4: no default.)
        StandaloneConfig config = new StandaloneConfig();
        StandaloneConfig.TomlSchemaRef ref = new StandaloneConfig.TomlSchemaRef();
        ref.setVersion("1.0.0");
        // location deliberately left null
        config.setTomlSchema(ref);
        config.setProjects(List.of());
        String toml = new ArrayOfTablesTomlEmitter().emit(config);
        assertFalse(toml.contains("location"), "version-only table must omit location:\n" + toml);
        assertValid(config, "[toml-schema] version only");
    }
}
