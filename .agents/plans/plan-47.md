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
