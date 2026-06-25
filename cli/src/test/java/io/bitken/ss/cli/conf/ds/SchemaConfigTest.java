package io.bitken.ss.cli.conf.ds;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the build-baked {@link SchemaConfig#SCHEMA_LOCATION} (plan-91 Task 4). The value is
 * variant-dependent (dev {@code file://} vs prod {@code https://} version-pinned URL); these
 * assertions hold for whichever variant this test build was compiled under.
 */
class SchemaConfigTest {

    @Test
    void schemaLocationIsAWellFormedUri() {
        assertNotNull(SchemaConfig.SCHEMA_LOCATION);
        URI uri = assertDoesNotThrow(() -> URI.create(SchemaConfig.SCHEMA_LOCATION));
        String scheme = uri.getScheme();
        assertTrue("file".equals(scheme) || "https".equals(scheme),
                "schema location must be a file:// or https:// URI, was: " + SchemaConfig.SCHEMA_LOCATION);
    }

    @Test
    void devLocationResolvesToAStagedSchemaPath() {
        // The test JVM runs the dev build (build.env unset), so the baked value is a file://
        // pointing at the staged dev payload schema. A prod-built test JVM emits https:// —
        // skip the file check there.
        URI uri = URI.create(SchemaConfig.SCHEMA_LOCATION);
        if (!"file".equals(uri.getScheme())) {
            return;
        }
        Path schema = Path.of(uri);
        assertTrue(schema.endsWith(Path.of("schemas", "shipsmooth.tosd")),
                "dev location must point at <payload>/schemas/shipsmooth.tosd, was: " + schema);
        // The dev payload may not be assembled when this unit test runs, so don't require the
        // file to exist — only that the path is the staged-copy location, not a test resource.
        assertFalse(schema.toString().contains("src/test/resources"),
                "dev location must not point at a test resource: " + schema);
    }

    @Test
    void schemaVersionIsSet() {
        assertEquals("1.0.0", SchemaConfig.SCHEMA_VERSION);
    }
}
