import org.gradle.api.Project

/**
 * The single source of truth for "is this a prod build?" across every module
 * (plan-75 Task 2). `build.env` is the one prod/dev signal: the release passes
 * `-Pbuild.env=prod`; the dev inner loop / `devBuild` never sets it (absent -> dev).
 *
 * Every build-variant decision (core's baked `EXPERIMENTAL_BUILD`, cli's jlink image
 * folder, and any future variant knob) derives from this one rule, so the meaning of
 * `build.env` is defined here once rather than re-`findProperty`'d per module — which
 * is the kind of drift that let the 0.3.17 prod leak ship. Deliberately NOT keyed on a
 * separate `experimental.enabled` property: `findProperty` can't tell a
 * `gradle.properties` value from a `-P` one, so a properties-file value would
 * permanently mask `build.env`.
 */
fun Project.isProdBuild(): Boolean =
    (findProperty("build.env") as String?) == "prod"

/**
 * Whether experimental surface is enabled for this build. Prod hides it
 * (`EXPERIMENTAL_BUILD=false` -> `--enable-experimental` hidden from `--help`); dev
 * shows it. The experimental *code* ships either way — this only toggles visibility.
 */
fun Project.experimentalEnabled(): Boolean = !isProdBuild()
