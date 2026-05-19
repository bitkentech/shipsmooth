# Plan 48: Wire ValidateRelease into PublishRelease

## Context

Plan 47 added `ValidateRelease` as a standalone `exec:java@validate-release` goal
intended to be run manually before packaging. However, when tested against the
current dev build output (`build/`), it passed — but that output was produced by
the default `dev` profile and contained experimental skills
(`experimental-start-parallel-dev`, `experimental-start-tla-dev`). The prod build
that `PublishRelease` produces internally (via `mvn compile -Pprod -P!dev`) hadn't
run yet.

The standalone `exec:java@validate-release` step is therefore ineffective as a
release gate: it checks stale or wrong build output rather than the prod build
that actually ships.

## Design

Call `ValidateRelease.validate()` directly from `PublishRelease.buildAndPackage()`
in Java, immediately after the `mvn compile -Pprod -P!dev` command completes and
before `PackageRuntime` runs. This guarantees:

- The build output being validated is exactly what `PublishRelease` just produced.
- No manual pre-step is required — validation is an integral, unforgeable part of
  the release.
- A placeholder leak or missing field aborts the release before any zip is created
  or any git state is modified on the `releases` branch.

The standalone `exec:java@validate-release` execution in `plugin-dist/pom.xml`
remains — it is still useful for ad-hoc checks during development.

Backlog issue: none (self-contained release tooling fix).

## Tasks

### Task 1: Call ValidateRelease.validate() inside PublishRelease.buildAndPackage() [Low]

- In `buildAndPackage()`, after the `mvn compile -Pprod -P!dev` `runCommand` call,
  add:
  ```java
  ValidateRelease.validate(repoRoot.resolve("build"), null);
  ```
  (Gemini output dir is `null` — Claude release only validates `build/`.)
- Update `PublishReleaseTest` to cover the validate call: add a test that a
  tampered `plugin.json` (placeholder leak injected after compile) causes
  `buildAndPackage` to throw before any zip is written.
- Verify: `mvn install -pl plugin-dist -am -Pprod -P'!dev' -DskipTests` then
  `mvn exec:java@publish-release -pl plugin-dist -Dshipsmooth.release.version=0.3.8
  -Pprod -P'!dev'` completes successfully.

### Task 2: Add --dangerous-skip-release-validation escape hatch [Low]

Add an opt-in flag to `PublishRelease` that bypasses `validateBuildOutput()` for
cases where validation itself is broken and blocking a release.

- Accept `--dangerous-skip-release-validation` as a command-line argument in
  `PublishRelease.main()` (not a system property — must be explicit at the call
  site, not accidentally inherited from env or pom).
- Pass a `boolean skipValidation` down to `buildAndPackage()` and branch around
  the `validateBuildOutput()` call.
- When the flag is present, print a prominent warning before the build:
  `"WARNING: release validation skipped — --dangerous-skip-release-validation was passed"`
- Unit test: verify that when `skipValidation=true`, `validateBuildOutput` is not
  called even if build output contains a placeholder leak (i.e. no exception thrown).
