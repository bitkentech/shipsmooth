package org.tomlschema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * De-risk tests for plan-91 Task 2: the vendored TOML Schema reference
 * implementation must load {@code shipsmooth.tosd} and validate conforming TOML.
 */
class TomlSchemaRefImplTest {

    @TempDir Path tmp;

    private Path schema() {
        try {
            return Path.of(getClass().getResource("/shipsmooth.tosd").toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void loadsShipsmoothSchema() {
        assertDoesNotThrow(() -> TomlSchema.load(schema()),
                "must load shipsmooth.tosd without error");
    }

    @Test
    void validatesConformingConfig() throws IOException {
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [[projects]]
                localPath = '/home/user/repo'
                remoteUrl = 'git@github.com:user/repo.git'
                stateDir = '/home/user/repo-shipsmooth'
                mode = 'external'

                [[projects]]
                localPath = '/home/user/repo2'
                mode = 'in-repo'
                """);

        ValidationResult result = TomlSchema.load(schema()).validate(config);
        assertTrue(result.isValid(), "conforming config must validate: " + result.errors());
    }

    @Test
    void validatesMinimalEntry() throws IOException {
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [[projects]]
                localPath = '/home/user/repo3'
                """);

        ValidationResult result = TomlSchema.load(schema()).validate(config);
        assertTrue(result.isValid(), "entry with only localPath must validate: " + result.errors());
    }

    @Test
    void emptyConfigIsValid() throws IOException {
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, "");

        ValidationResult result = TomlSchema.load(schema()).validate(config);
        assertTrue(result.isValid(), "empty config must be valid (projects is optional)");
    }
}
