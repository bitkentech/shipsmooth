# plan-91 — TOML Schema for shipsmooth.toml

## Context

Create a formal TOML Schema (`shipsmooth.tosd`) for `shipsmooth.toml` — the user-level
configuration file. The schema serves two purposes:

1. **Documentation**: a machine-readable spec of the config format
2. **Validation safety net**: test-time verification that emitter output matches the schema

### Design decisions

**Schema location**: `cli/src/test/resources/shipsmooth.tosd` (CLI module's test resources, since
all TOML config code lives in `cli`; `core` has zero TOML references).

**Schema reference in config**: `shipsmooth.toml` gains a `[toml-schema]` table with
`version` and `location` fields, per the TOML Schema spec convention. Emitted by
`ArrayOfTablesTomlEmitter`, parsed by Jackson automatically.

**Validation approach**: Test-time only. The [TOML Schema Java reference
implementation](https://github.com/brunoborges/toml-schema/tree/main/reference-implementations/java)
is added to the test classpath (along with `org.tomlj:tomlj:1.1.1`). A conformance test
generates config variants through the emitter and validates each against `shipsmooth.tosd`.

**Not shipping validation**: The shipped jlink image includes neither Tomlj, the ref impl,
nor the `.tosd`. Production `ConfigWriter` and `ProjectDataStoreResolver` are unchanged.

### Backlog reference

N/A — infrastructure / tooling improvement.

## Tasks

### Task 2: Integrate TOML Schema reference implementation in tests [Medium]

Add the TOML Schema reference implementation and Tomlj as test-scope dependencies.

- Add `org.tomlj:tomlj:1.1.1` as `testImplementation` in `cli/build.gradle.kts`
- Copy reference implementation source files (`org.tomlschema.*`) into
  `cli/src/test/java/org/tomlschema/`
- Copy `shipsmooth.tosd` into `cli/src/test/resources/` (or reference by relative path)
- Verify the validator can load `shipsmooth.tosd` and parse a simple TOML string

*Risk: Medium — ref impl may need minor path or classpath adjustments for test context.*

### Task 1: Add `[toml-schema]` header to emitter and data model [Low]

Wire the schema reference into the config file itself.

- Add `TomlSchemaRef` nested class to `StandaloneConfig` with `version` and `location` fields
- Update `ArrayOfTablesTomlEmitter` to emit the `[toml-schema]` block before `[[projects]]`
- Update `ConfigWriter.writeExternal()` / `writeInRepo()` to set the schema version and URL
  on new entries
- Jackson deserializes `[toml-schema]` automatically — no read-side changes needed

*Risk: Low — data model + emitter changes follow existing patterns exactly.*

### Task 3: Write schema conformance test [Low]

A test that validates emitter output against the schema.

- `SchemaConformanceTest.java` constructs `StandaloneConfig` variants:
  - In-repo entry (`localPath` + `mode = "in-repo"`)
  - External entry (`localPath` + `stateDir` + `mode = "external"`)
  - Back-compat entry (no `mode`, just `stateDir`)
  - Minimal entry (just `localPath`)
  - Empty config (zero projects)
- Each variant goes through the emitter, then validated via
  `TomlSchema.load(tosd).validate(tomlString)`
- Assert `result.isValid()`

*Risk: Low — standard test pattern once infrastructure is in place.*

### Task 4: Make the schema available to the app for reference [Medium]

*Depends-on: 1*

The emitted `[toml-schema]` block currently sets `location = './shipsmooth.tosd'`, a relative
path that resolves next to the user's `~/.config/shipsmooth/shipsmooth.toml` — but the `.tosd`
is never installed there, so the reference is a dangling pointer. Make the schema reachable
at a stable, published URL.

#### Spec finding — `location` is a real spec key, URI-valued

Per the upstream TOML Schema spec (`brunoborges/toml-schema`), `[toml-schema]` in a *config*
file may carry exactly `version`, `location`, and a `meta` subtable — nothing else. `location`
is defined as a URI ("either remote URL or local path") naming "which schema file to use for
validation". So a remote `https://` URL is spec-conformant and first-class, not a mere comment.

Caveat: the **vendored Java reference impl** (`SchemaLoader`) never reads `location` — it loads
the schema from a `Path` handed to it directly and rejects any key other than `version`/`meta`
*inside a `.tosd` file's* `[toml-schema]`. So at runtime nothing in shipsmooth (nor the ref impl)
dereferences `location`; it is a documentary pointer for humans / external tooling. That is fine
— a single stable public URL is the right shape.

#### Dev/prod split — the `location` differs by build variant

A single URL for every build is **wrong**: a dev build that emitted the `releases` URL would point
at whatever prod last published, not the working-tree schema under test — the same dangling-pointer
class of bug, harder to notice. So `location` is **build-variant-dependent**:

- **PROD** (`build.env=prod`, the release path): emit
  `https://raw.githubusercontent.com/bitkentech/shipsmooth/releases/dist/schemas/shipsmooth.tosd`
  (branch ref `releases`, not a per-version tag — nothing fetches it and it must track latest).
  Published once into the Claude `dist/` payload at `dist/schemas/shipsmooth.tosd`.
- **DEV** (`build.env` absent): physically copy `shipsmooth.tosd` into **each platform's** payload
  under `schemas/`, and emit an absolute `file://` `location` pointing at that staged copy — so a
  dev build's emitted config reflects the schema in the tree being built, resolvable offline.

The `location` must **not** point at `cli/src/test/resources/shipsmooth.tosd` (build-internal test
path) in the emitted config, and the file must **never** be written next to the user's
`shipsmooth.toml`.

Why a runtime CLI can't just use a payload-relative path: `ConfigWriter` runs from the **jlink
image** in `~/.cache/shipsmooth/<ver>/`, a *separate* tree from the plugin payload
(`build-claude-dev/`, `dist/`). It has no relative anchor to the staged `schemas/`. Hence the value
is **baked at build time**, absolute.

#### How the value is injected — reuse the `BuildEnv` → `Build.java` machine

The location string is the same *kind* of build-variant value the repo already bakes (`VERSION`,
`EXPERIMENTAL_BUILD`). Reuse that machine rather than threading anything through `PublishRelease`:

1. **`buildSrc/BuildEnv.kt`** — add a derived `schemaLocation` property next to `experimentalEnabled`
   (the one spot the build-variant meaning lives, by design): `PROD` → the releases URL; `DEV` →
   the absolute `file://` to the staged copy.
2. **`core/src/main/java-templates/.../Build.java`** + `generateBuildConstants.expand(...)` — add
   `public static final String SCHEMA_LOCATION = "${schema.location}";`.
3. **`ConfigWriter`** (in `cli`, which depends on `core`) — emit `Build.SCHEMA_LOCATION` in place of
   the hardcoded `./shipsmooth.tosd`. No Dagger, no `PublishRelease` involvement — `build.env=prod`
   at compile time bakes the prod URL straight into the released CLI.
4. Adjust the conformance / integration tests (and any fixture) that assert the old `location`.

#### Per-platform dev staging + prod publish

- **Dev staging:** copy `cli/src/test/resources/shipsmooth.tosd` into each dev payload's `schemas/`
  folder (Claude `build-claude-dev/`, and the other hosts as their dev assemblies are touched),
  from the one source so copies can't drift. The baked dev `file://` must agree with this path.
- **Prod publish — release-layout finding:** `PublishRelease.syncDistAndPublish` rebuilds `dist/`
  each release by copying a fixed list `SHIPPED_BUILD_SUBPATHS` out of `build/`, then pushes
  `releases`. `schemas/` was **not** in that list, so the prod URL would be a dead link. Add
  `"schemas"` to `SHIPPED_BUILD_SUBPATHS` **(done in de-risk draft)** and stage
  `build/schemas/shipsmooth.tosd` during `assembleClaudeProd`. With both, the prod schema is
  auto-synced and version-locked — re-copied from `build/` on every cut.
- Per-host note: prod publishes separate payloads (Claude `dist/`, Codex `dist-codex/`, Windows
  orphan branch, OpenCode npm-only). The **prod URL** is a single canonical pointer all hosts share
  (only physically published under Claude's `dist/`); the **dev** per-platform copies are for
  offline/local-file resolution in each dev payload.

*Risk: Medium — touches build-constant generation (`BuildEnv`, `Build.java`), per-platform dev
staging, release wiring (`PublishRelease`, Claude assembly), and a user-visible config value.*

## Open questions

- When adding `toml-schema` version/URL to emitted config, should we bump it on every
  release or keep it stable? (Decision: defer — user indicated "not for now" on schema
  version compatibility.)
