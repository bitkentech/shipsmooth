# Plan 49: Move release exec executions out of claude profile in plugin-dist

## Context

`exec:java@publish-release`, `exec:java@package-runtime`, and the darwin variants
are declared inside the `claude` profile in `plugin-dist/pom.xml`. When invoked
directly on the command line (`mvn exec:java@publish-release ...`), Maven reads the
execution configuration from the pom. If the pom in the local Maven repo was
installed with `-Pprod` active, the profile-baked effective pom makes the
executions visible — but this is fragile and non-obvious.

The two-step release pattern (`mvn install ... -Pprod` then `mvn exec:java@...
-Pprod`) only works because `install` writes a flattened effective pom into the
local repo. If the install step uses the wrong profile, the wrong executions get
baked in. This was discovered when `exec:java@publish-release` failed with
"parameters 'mainClass' ... missing or invalid" after the install had been run
against stale `0.3.7` artifacts.

`validate-release` (added in plan 47 and corrected in plan 47/task 5) was already
moved to the top-level `<build>` for exactly this reason. The release packaging
executions need the same treatment.

The `maven-resources-plugin` executions inside the `claude` profile
(`copy-scripts`, `copy-ts-source`, `copy-package-json`) are correctly placed —
they are Claude-specific copy steps that should only run when the `claude` profile
is active. Only the `exec-maven-plugin` release executions need to move.

## Design

Move the four `exec-maven-plugin` executions out of the `claude` profile into the
top-level `<build>` section of `plugin-dist/pom.xml`, alongside the existing
`validate-release` execution. They are already guarded by system properties
(`build.outputDir`, JDK paths) that are only populated when a platform profile is
active — running them without `-Pprod` will still fail with missing paths, not
silently misbehave.

Backlog issue: none (self-contained build tooling fix).

## Tasks

### Task 1: Move package-runtime and publish-release exec executions to top-level build [Low]

- In `plugin-dist/pom.xml`, move the four `exec-maven-plugin` executions
  (`package-runtime`, `package-runtime-darwin-x64`, `package-runtime-darwin-arm64`,
  `publish-release`) from inside the `claude` profile into the top-level `<build>`
  section, after `validate-release`.
- Remove the now-empty `exec-maven-plugin` `<plugin>` block from the `claude`
  profile. Leave the `maven-resources-plugin` executions in the profile untouched.
- Verify: `mvn install -pl plugin-dist -am -Pprod -P'!dev' -DskipTests` then
  `mvn exec:java@publish-release -pl plugin-dist -Dshipsmooth.release.version=0.3.8
  -Pprod -P'!dev'` completes successfully.
