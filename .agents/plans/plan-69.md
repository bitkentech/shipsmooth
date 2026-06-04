# Plan 69 — Realign the Gemini build/release with the jlink runtime architecture

## Context

**Backlog feature (Local mode):** Gemini CLI extension parity — the Gemini build
and release must track the current native-jlink runtime architecture, not the
retired TypeScript-CLI model. No external backlog tracker; recorded here per
Core Invariant #3.

### Background — how we got here

The runtime was once a TypeScript CLI shipped as `dist/*.js` (`init.js`, `show.js`,
`xml-utils.js`, …) that imported `fast-xml-parser` and therefore required an
`npm install` at session start. Two artefacts encode that dead model:

- `skills/pkg/scripts/src/main/resources/package.json` (a Maven-filtered template
  declaring `fast-xml-parser` + `typescript`), copied into every build output by
  `packaging/pom.xml`'s `copy-package-json` execution.
- `gemini/src/main/resources/gemini-extension/hooks/hooks.json`, a hand-written
  SessionStart hook that copies that `package.json` into `~/.cache/shipsmooth/`,
  runs `npm install --prefix`, and stages `dist/*.js` into `~/.cache/shipsmooth/dist/`.

Today the runtime is a native jlink binary: the SKILL's `cliBin` resolves to
`${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-<version>/bin/shipsmooth`
(`Os.Posix.cliBinPath`), installed by `dist/session-start.js` downloading the
release zip. The only non-`node:` import in `session-start.js` is the
esbuild-bundled `./adm-zip-bundle`; nothing imports `fast-xml-parser`.

### The actual defects

1. **Renderer/release phase mismatch.** `Target` (renders `SKILL.md`, writes the
   correct `node "${extensionPath}/dist/session-start.js"` `hooks.json`, and writes
   `session-start-config.json`) is bound to the **`compile`** phase
   (`skills/pkg/pom.xml` `render-plugin-resources`). But the Gemini release path —
   `devtools/scripts/release-gemini.sh`, `devtools/scripts/smoke-gemini.sh`, and
   DEVELOPMENT.md — all run only **`process-resources`**, which never reaches
   `compile`. A `process-resources` Gemini build therefore omits the rendered
   skill and config and leaves the fossil npm hook in place.

   *Evidence:* `mvn compile -P gemini,!dev,!claude` yields a correct
   `build-gemini/` (hook runs `session-start.js`, cliBin is `runtime-<ver>/bin`,
   `session-start-config.json` present); `mvn process-resources` does not. The
   currently-published `bitkentech/shipsmooth-gemini` repo still carries the old
   TS-CLI `dist/*.js` + npm `package.json`, confirming releases were cut before the
   jlink migration.

2. **Dead `package.json` ship + `npm install`.** Even on Claude (where the file is
   shipped but inert) and via the Gemini fossil hook, `package.json` is never read
   by the current runtime. The shipped copy is also half-unfiltered (`name`/`description`
   keep the literal `@plugin.name@` placeholder).

3. **Stale `fast-xml-parser` runtime dependency** in the dev
   `skills/pkg/scripts/package.json` — nothing under `scripts/tasks/` imports it.

4. **Dead `shipsmooth.dist.path` property** (`~/.cache/shipsmooth/dist`), set in
   three `pom.xml` profiles, referenced nowhere. A devostat-rename leftover.

5. **Stale smoke-test assertions.** `smoke-gemini.sh` asserts `SKILL.md` contains
   `~/.cache/shipsmooth/dist` — the old cliBin path. With the jlink model the SKILL
   uses `runtime-<ver>/bin/shipsmooth`, so the assertion is wrong.

### Goal

Make the Gemini build/release produce a correct artefact for the jlink runtime,
delete the fossils on all platforms, fix the supporting scripts/docs, and cut a
corrected Gemini release.

### Non-goals / invariants

- Claude and Windows runtime behaviour must not change (they already ignore
  `package.json`; the Claude hook already runs `session-start.js`).
- Dev `scripts/package.json` `devDependencies` (esbuild, adm-zip, types) and
  `scripts` (build/bundle/test) are the build toolchain and stay. `typescript`
  stays (the build/test scripts invoke `tsc`).
- The Gemini SessionStart hook end state is exactly Target's rendered
  `node "${extensionPath}/dist/session-start.js"` — no npm, no package.json.

## Tasks

### Task 1: Make the Gemini release run the renderer [High]

Ensure the Gemini build reaches `Target`. Change `devtools/scripts/release-gemini.sh`
and `devtools/scripts/smoke-gemini.sh` to run `mvn compile -P 'gemini,!dev,!claude'`
(not `process-resources`), and update the DEVELOPMENT.md Gemini build commands to
match. Verify a clean `build-gemini/` then contains a rendered `skills/start/SKILL.md`,
a `hooks/hooks.json` that runs `session-start.js`, and `dist/session-start-config.json`.

High risk: this is the core correctness fix and changes what the release script
ships; a wrong phase or profile silently reproduces the broken tree.

### Task 2: Delete the fossil Gemini npm hook [Medium]

*Depends-on: 1*

Remove `gemini/src/main/resources/gemini-extension/hooks/hooks.json` (the npm
install + package.json + dist-staging command) and its `copy-gemini-hooks`
executions in `gemini/pom.xml` (both prod and dev). Confirm the rendered
`build-gemini/hooks/hooks.json` (from Target) is the one that survives and runs
`session-start.js`.

Medium risk: must confirm nothing else depends on the hand-written hook and that
Target's hook is correctly emitted for Gemini after Task 1.

### Task 3: Stop copying `package.json` into build output [Low]

Remove the `copy-package-json` execution from `packaging/pom.xml` and delete the
orphan template `skills/pkg/scripts/src/main/resources/package.json` (and its empty
`resources/` dir). Verify no `build*/package.json` is emitted on any platform.

Low risk: pure build-wiring removal; no current code path reads the file.

### Task 4: Remove the dead `shipsmooth.dist.path` property [Low]

Delete the `<shipsmooth.dist.path>` property from all three `pom.xml` profiles
(Claude, Windows, Gemini). Confirm via grep it has no remaining references and
all builds still succeed.

Low risk: removing an unreferenced property.

### Task 5: Drop the stale `fast-xml-parser` runtime dep [Low]

Remove `fast-xml-parser` from `dependencies` in `skills/pkg/scripts/package.json`
and refresh `package-lock.json`. Confirm `npm run build` and `npm test` still pass.

Low risk: removing an unused declared dependency, covered by the TS build+test.

### Task 6: Fix smoke-gemini.sh assertions [Low]

*Depends-on: 1*

Update `devtools/scripts/smoke-gemini.sh` so its SKILL assertions match the jlink
model: assert the rendered SKILL references `runtime-<version>/bin/shipsmooth`
(not `~/.cache/shipsmooth/dist`), and that the hook runs `session-start.js`.
Run the smoke test to green.

Low risk: test/script alignment, no product code.

## Verification

- `mvn compile -P 'gemini,!dev,!claude'` and the updated `release-gemini.sh`/
  `smoke-gemini.sh` produce a `build-gemini/` whose `hooks/hooks.json` runs
  `node "${extensionPath}/dist/session-start.js"`, whose `skills/start/SKILL.md`
  uses `runtime-<ver>/bin/shipsmooth`, and whose `dist/` has
  `session-start-config.json`.
- No `package.json` in any `build*/` output; no `npm install` in any hook.
- `grep -rn shipsmooth.dist.path` returns nothing (outside plan history).
- `cd skills/pkg/scripts && npm run build && npm test` pass.
- `./devtools/scripts/smoke-gemini.sh` passes.
- Full Java suite green (`mvn test`).
- Corrected Gemini release cut via `release-gemini.sh <version>` (post-merge).
