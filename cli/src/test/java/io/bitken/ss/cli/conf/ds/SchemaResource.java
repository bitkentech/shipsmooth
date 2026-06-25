package io.bitken.ss.cli.conf.ds;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Locates {@code shipsmooth.tosd} for tests. The schema is a CLI <em>main</em> resource
 * (it ships in the jar / jlink image and is staged into each plugin payload), so tests
 * read it off the classpath rather than from a hard-coded {@code src/test/resources} path.
 */
public final class SchemaResource {

    private SchemaResource() {}

    /** The schema file as a {@link Path}, resolved from the test classpath. */
    public static Path schemaPath() {
        URL url = SchemaResource.class.getResource("/shipsmooth.tosd");
        if (url == null) {
            throw new IllegalStateException("shipsmooth.tosd not found on the classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("invalid shipsmooth.tosd URL: " + url, e);
        }
    }
}
