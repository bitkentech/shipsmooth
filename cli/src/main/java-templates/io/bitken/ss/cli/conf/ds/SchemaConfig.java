package io.bitken.ss.cli.conf.ds;

/**
 * The {@code [toml-schema] location} value this CLI build emits into {@code shipsmooth.toml},
 * baked per build variant (plan-91 Task 4). {@code build.env=prod} bakes the version-pinned
 * {@code releases} URL; a dev build bakes a {@code file://} to the schema staged into the dev
 * payload. Generated from {@code SchemaConfig.java} template via {@code generateSchemaConfig}.
 */
public final class SchemaConfig {
    /** The TOML Schema version advertised in the emitted {@code [toml-schema]} table. */
    public static final String SCHEMA_VERSION = "1.0.0";

    /** The {@code location} URI emitted in {@code [toml-schema]} for this build. */
    public static final String SCHEMA_LOCATION = "${schema.location}";

    private SchemaConfig() {}
}
