# Plan 47: Fix prod/dev build missing JS files in build/dist/

## Context

After plan-46 (cross-platform install using `adm-zip`), the SessionStart hook
started failing with `node:internal/modules/cjs/loader:1137` on both dev and
prod plugin installs.

**Root cause:** `copy-dist` (copies compiled JS from `plugin-node/dist/` into
`build/dist/`) was gated inside the `claude` profile in `plugin-dist/pom.xml`,
which had `<activeByDefault>true</activeByDefault>`. However, explicitly passing
`-Pdev` or `-Pprod` on the command line suppresses all `activeByDefault` profiles
in Maven. So `build/dist/` only received `session-start-config.json` (written by
`ResourceBuilder` in `plugin-skill`) but never the compiled JS files. The hook
command `node "${CLAUDE_PLUGIN_ROOT}/dist/session-start.js"` then failed because
`session-start.js` was absent.

**Secondary issue:** plan-46 replaced shell `unzip` with the `adm-zip` npm
package, but `adm-zip` was never made available at the install location. Even if
JS files were present, `require('adm-zip')` would fail at runtime.

## Design

Use the `<skip>` property pattern (supported per-execution in
`maven-resources-plugin` 3.0.0+) to move both copy steps out of profile
activation and into the default build, controlled by a `${skip.copy-dist}`
property inherited from the root pom profiles.

- Root `pom.xml`: default `skip.copy-dist=true`; set to `false` in `dev`,
  `prod`, `gemini`, `gemini-dev` profiles.
- `plugin-dist/pom.xml`: new top-level `<build>` section with two executions:
  - `copy-dist` — JS from `plugin-node/dist/` → `build/dist/`
  - `copy-adm-zip` — `plugin-node/node_modules/adm-zip/` → `build/dist/node_modules/adm-zip/`
- Remove the now-redundant `copy-dist` executions from the `claude` and `gemini`
  profiles in `plugin-dist/pom.xml`.

Backlog issue: none (self-contained build fix).

## Tasks

### Task 1: Fix copy-dist profile activation and bundle adm-zip [Low]

- Move `copy-dist` out of the `claude` and `gemini` profiles in
  `plugin-dist/pom.xml` into the default `<build>` section, gated by
  `${skip.copy-dist}`.
- Add `copy-adm-zip` execution to bundle `adm-zip` alongside the JS files.
- Add `skip.copy-dist` property to root `pom.xml` (default `true`, `false` in
  all four platform profiles).
- Verify: `mvn -Pdev compile` → `build/dist/` contains all JS + `adm-zip`;
  `mvn -Pgemini compile` → `build-gemini/dist/` contains all JS + `adm-zip`;
  `node build/dist/session-start.js` installs runtime successfully.

## Lock exec-maven-plugin version in pluginManagement

When invoking `exec:java@<id>` directly on the command line (outside a lifecycle
phase), Maven resolves the plugin version from central metadata (currently `3.6.3`)
rather than honouring the version declared in the module pom. The `3.6.3` instance
loads without the execution configuration, so `mainClass` is missing and the build
fails.

### Task 5: Lock exec-maven-plugin version in root pom pluginManagement [Low]

- Add `exec-maven-plugin:3.3.0` to `<pluginManagement>` in the root `pom.xml`.
- Verify: `mvn exec:java@validate-release -pl plugin-dist -Pprod -P'!dev'` passes
  without needing a fully-qualified groupId/version prefix.
- No unit test needed — verified by the smoke-test invocation above.

## Enforcer rules

Two `maven-enforcer-plugin` rules bound to the `validate` phase in the root pom
to make the build fully deterministic:

- **`requirePluginVersions`** — fails if any plugin is invoked without an explicit
  version declared in pom or pluginManagement. Directly prevents the exec-maven-plugin
  version-drift incident. Plugins that Maven activates implicitly (surefire, jar,
  resources, compiler, install, deploy, site) must also be pinned in pluginManagement.
- **`dependencyConvergence`** — fails if any transitive dependency resolves to
  conflicting versions across the reactor. Keeps the classpath deterministic and
  surfaces hidden version conflicts early.

Both rules are additive — they fail the build loudly on violation rather than
silently picking a version.

### Task 6: Add requirePluginVersions enforcer rule [Low]

- Add `maven-enforcer-plugin` to `<pluginManagement>` in root `pom.xml` (pin version).
- Add an execution bound to `validate` phase with the `enforce` goal and
  `requirePluginVersions` rule.
- Pin any implicitly-used plugins (surefire, jar, resources, compiler, install,
  deploy) in root `pluginManagement` to satisfy the rule.
- Verify: `mvn validate` passes; deliberately removing a version triggers a clear
  failure message.

### Task 7: Add dependencyConvergence enforcer rule [Low]

- Add `dependencyConvergence` rule to the same enforcer execution as task 6.
- Verify: `mvn validate` passes across the reactor.
- If any convergence violations surface, resolve them (add `<dependencyManagement>`
  entries to root pom to pin the winning version).

## Validate Release

Add a `ValidateRelease` Java class in `plugin-dist` that asserts all placeholder
substitutions in generated JSON manifests resolved correctly before packaging begins.

### Task 4: Add ValidateRelease pre-packaging check [Low]

- New class `plugin-dist/src/main/java/io/bitken/shipsmooth/dist/ValidateRelease.java`.
- Reads `build.outputDir` and `build.gemini.outputDir` system properties.
- Validates `build/.claude-plugin/plugin.json`: `name`, `version`, `description` are
  present, non-empty, and contain no `${`.
- Validates `build/.claude-plugin/marketplace.json`: top-level `name`, `plugins[0].name`,
  `plugins[0].description`.
- Validates `build-gemini/gemini-extension.json` if the file exists (skip silently
  otherwise): `name`, `version`, `description`.
- Fails with a precise message naming the file and field on any violation.
- Wire as a `validate-release` `exec:java` execution (no `<phase>`) in the `claude`
  profile of `plugin-dist/pom.xml`, passing the same system properties as the
  existing `package-runtime` execution.
- Unit test: `ValidateReleaseTest` covering valid JSON, missing field, placeholder
  leak, and absent gemini file (should pass silently).
- Release command becomes:
  ```
  mvn -pl plugin-dist -am compile \
    exec:java@validate-release \
    exec:java@package-runtime \
    exec:java@package-runtime-darwin-x64 \
    exec:java@package-runtime-darwin-arm64 \
    exec:java@publish-release \
    -Pprod -P'!dev'
  ```
