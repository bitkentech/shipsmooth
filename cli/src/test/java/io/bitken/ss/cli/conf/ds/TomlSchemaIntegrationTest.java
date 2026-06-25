package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end feature test for plan-91: emitter output must carry a {@code [toml-schema]}
 * header and the result must validate against {@code shipsmooth.tosd} via the TOML Schema
 * reference implementation.
 *
 * <p>All three plan-91 tasks are now delivered: Task 2 (reference impl dependency),
 * Task 1 ({@code [toml-schema]} header in emitter), and Task 3 (schema conformance).
 * This test should stay green.
 */
class TomlSchemaIntegrationTest {

    @TempDir Path tmp;

    @Test
    void emittedConfig_hasTomlSchemaHeader_andValidatesAgainstSchema() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("test-repo"));
        Path stateDir = Files.createDirectories(tmp.resolve("test-state"));

        // Write an external-mode entry via ConfigWriter.
        new ConfigWriter(() -> config)
                .writeExternal(repo, Optional.of("git@github.com:user/project.git"), stateDir);

        String toml = Files.readString(config);

        // 1. The emitted TOML must contain a [toml-schema] header (Task 1).
        assertTrue(toml.contains("[toml-schema]"),
                "emitted config must include a [toml-schema] header:\n" + toml);

        // 2. The emitted TOML must validate against the schema file (Tasks 2 + 3).
        Path schemaPath = Path.of("src/test/resources/shipsmooth.tosd");
        Path tomlFile = tmp.resolve("validated.toml");
        Files.writeString(tomlFile, toml);
        org.tomlschema.TomlSchema schema = org.tomlschema.TomlSchema.load(schemaPath);
        var result = schema.validate(tomlFile);
        assertTrue(result.isValid(), "emitted config must conform to shipsmooth.tosd:\n" + toml);
    }
}
