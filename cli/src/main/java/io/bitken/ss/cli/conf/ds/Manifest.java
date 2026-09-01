package io.bitken.ss.cli.conf.ds;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.bitken.ss.Build;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The owned-folder marker (PB-360): {@code manifest.toml} at the data root.
 *
 * <p>Small and write-only. {@code store init} stamps it into a folder it
 * creates; {@link ProjectDataStoreResolver} reads it as a recorded fact that
 * shipsmooth owns the folder rather than inferring ownership from a {@code
 * plans/} subdirectory. Unlike {@code shipsmooth.toml} there is no upsert and
 * no unknown-but-valid content to preserve, so it is emitted from a fixed
 * template and parsed leniently — a missing or unparseable marker is simply
 * "no usable marker".
 */
public final class Manifest {

    /** The {@code kind} value marking a shipsmooth-owned state folder. */
    public static final String KIND_STATE_STORE = "state-store";
    /** The manifest's own schema version. */
    public static final String SCHEMA_VERSION = "1";

    private static final TomlMapper TOML = new TomlMapper();

    private Shipsmooth shipsmooth;
    private SchemaRef manifestSchema;

    public Shipsmooth getShipsmooth() { return shipsmooth; }
    public void setShipsmooth(Shipsmooth shipsmooth) { this.shipsmooth = shipsmooth; }

    @JsonProperty("manifest-schema")
    public SchemaRef getManifestSchema() { return manifestSchema; }
    public void setManifestSchema(SchemaRef manifestSchema) { this.manifestSchema = manifestSchema; }

    /** True when this marker names a shipsmooth-owned state folder. */
    public boolean isStateStore() {
        return shipsmooth != null && KIND_STATE_STORE.equals(shipsmooth.getKind());
    }

    /** The {@code [shipsmooth]} table. */
    public static final class Shipsmooth {
        private String kind;
        private String cliVersion;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }

        @JsonProperty("cli-version")
        public String getCliVersion() { return cliVersion; }
        public void setCliVersion(String cliVersion) { this.cliVersion = cliVersion; }
    }

    /** The {@code [manifest-schema]} table. */
    public static final class SchemaRef {
        private String version;

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }

    /**
     * Lenient read: a missing, unreadable, or unparseable file resolves as
     * {@link Optional#empty()} — the same spirit as the resolver's
     * {@code shipsmooth.toml} read path.
     */
    public static Optional<Manifest> read(Path path) {
        try {
            return Optional.ofNullable(TOML.readValue(path.toFile(), Manifest.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The manifest body this CLI build stamps into a folder it creates. */
    public static String currentBody() {
        return render(Build.VERSION);
    }

    /** Atomically (over)write the current manifest at {@code path}. */
    public static void write(Path path) throws IOException {
        writeAtomically(path, currentBody());
    }

    /**
     * The fixed template — single-quoted literals and one blank line after
     * each table, matching the {@code shipsmooth.toml} emitter's layout so the
     * two files look alike. {@code cliVersion} is a plain semver string, never
     * containing a quote, so a literal is always safe.
     */
    private static String render(String cliVersion) {
        return "[shipsmooth]\n"
                + "kind = '" + KIND_STATE_STORE + "'\n"
                + "cli-version = '" + cliVersion + "'\n"
                + "\n"
                + "[manifest-schema]\n"
                + "version = '" + SCHEMA_VERSION + "'\n"
                + "\n";
    }

    /**
     * Sibling temp file, then atomic rename — the same guarantee
     * {@link ConfigWriter} gives {@code shipsmooth.toml} (plan-87): a failed
     * write never leaves a truncated marker behind.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "manifest", ".tmp");
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
