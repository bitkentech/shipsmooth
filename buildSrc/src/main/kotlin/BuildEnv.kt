import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The build variant — the single prod/dev signal across every module (plan-75 Task 2).
 * The release passes `-Pbuild.env=prod`; the dev inner loop / `devBuild` never sets it
 * (absent -> dev). Every build-variant decision (core's baked `EXPERIMENTAL_BUILD`,
 * cli's jlink image folder, the render variants' experimental surface, and any future
 * knob) derives from this one enum, so the meaning of `build.env` is defined here once
 * rather than re-parsed per module — the kind of drift that let the 0.3.17 leak ship.
 *
 * The values cross the Gradle/JVM boundary as strings (a `-P` property and the
 * `build.env` system property handed to Target), so each carries its wire [value] and
 * is parsed back via [from].
 *
 * Deliberately NOT keyed on a separate `experimental.enabled` property: `findProperty`
 * can't tell a `gradle.properties` value from a `-P` one, so a properties-file value
 * would permanently mask `build.env`.
 */
enum class BuildEnv(val value: String) {
    DEV("dev"),
    PROD("prod");

    /** Experimental surface ships in every build; this only toggles its visibility
     *  (`EXPERIMENTAL_BUILD` -> whether `--enable-experimental` shows in `--help`, and
     *  whether the skill payload documents experimental commands). Dev shows it. */
    val experimentalEnabled: Boolean get() = this == DEV

    /**
     * The `[toml-schema] location` value baked into `Build.SCHEMA_LOCATION` and emitted by
     * `ConfigWriter` (plan-91 Task 4). It is build-variant-dependent on purpose:
     *
     * - **PROD** -> the raw-GitHub URL pinned to this build's **`v<version>` release tag**, where
     *   the release publishes `dist/schemas/shipsmooth.tosd` (the tag and the `releases` branch are
     *   pushed at the same commit, so the tag tree carries the payload). Pinning to the version tag
     *   — not the moving `releases` branch — means a config written by version X points forever at
     *   X's own schema, instead of silently drifting to whatever a later release published.
     * - **DEV** -> an absolute `file://` URL to the schema staged into the dev payload, so a dev
     *   build's emitted config points at the schema in the tree being built (resolvable offline),
     *   not at whatever prod last published.
     *
     * [version] is the plugin version baked into this build (same value as `Build.VERSION`);
     * [devStagedSchema] is the absolute path the dev build stages `shipsmooth.tosd` to (ignored
     * for PROD).
     */
    fun schemaLocation(version: String, devStagedSchema: java.io.File): String = when (this) {
        PROD -> prodSchemaUrl(version)
        DEV -> devStagedSchema.toURI().toString()
    }

    companion object {
        /**
         * The PROD `[toml-schema] location` for a given release [version] — the raw-GitHub URL
         * pinned to the `v<version>` tag, where the release publishes the schema
         * (`dist/schemas/shipsmooth.tosd`). The version pin keeps each release's emitted config
         * pointing at that release's own schema. Built in one place so the URL the CLI bakes and
         * the path the release stages can be checked against each other.
         */
        fun prodSchemaUrl(version: String) =
            "https://raw.githubusercontent.com/bitkentech/shipsmooth/v$version/dist/schemas/shipsmooth.tosd"

        /**
         * Parse a `build.env` string. Absent (null) -> [DEV] (the inner-loop default).
         * An unrecognised value fails loudly at configuration rather than silently
         * resolving to a half-meant variant.
         */
        fun from(value: String?): BuildEnv {
            if (value == null) return DEV
            return entries.firstOrNull { it.value == value }
                ?: throw GradleException(
                    "Unknown build.env '$value' — expected one of " +
                        entries.joinToString(", ") { it.value } + "."
                )
        }
    }
}

/** The build variant for this project, read from the `build.env` property. */
fun Project.buildEnv(): BuildEnv = BuildEnv.from(findProperty("build.env") as String?)

/** Whether this is a prod build (the release path). */
fun Project.isProdBuild(): Boolean = buildEnv() == BuildEnv.PROD

/** Whether experimental surface is enabled for this build. */
fun Project.experimentalEnabled(): Boolean = buildEnv().experimentalEnabled
