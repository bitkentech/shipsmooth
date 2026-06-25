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
  `https://raw.githubusercontent.com/bitkentech/shipsmooth/v<version>/dist/schemas/shipsmooth.tosd`
  — pinned to **this build's `v<version>` release tag**, not the moving `releases` branch. The
  release pushes the tag and the `releases` branch at the same commit, so the tag tree carries
  `dist/schemas/shipsmooth.tosd`. Pinning means a config written by version X points forever at
  X's own schema instead of drifting to whatever a later release publishes. Published into the
  Claude `dist/` payload at `dist/schemas/shipsmooth.tosd`.
- **DEV** (`build.env` absent): physically copy `shipsmooth.tosd` into **each platform's** payload
  under `schemas/`, and emit an absolute `file://` `location` pointing at that staged copy — so a
  dev build's emitted config reflects the schema in the tree being built, resolvable offline.

The `location` must **not** point at `cli/src/test/resources/shipsmooth.tosd` (build-internal test
path) in the emitted config, and the file must **never** be written next to the user's
`shipsmooth.toml`.

Baking is legitimate because the **CLI is rebuilt per variant**: `PublishRelease.jlinkBuildCommand`
rebuilds every `:cli:image_<host>` with `-Pbuild.env=prod`, and dev/prod images live in separate
folders (`cli/build/jlink-image-<host>` vs `…-prod`). So `build.env` is known when the CLI compiles
— a per-variant build constant bakes the dev value into the dev image and the prod URL into the prod
image.

#### Schema source moves out of test resources

`shipsmooth.tosd` moves from `cli/src/test/resources/` to **`cli/src/main/resources/`** — it is a
shipped CLI artifact now, not a test fixture. The conformance/integration/ref-impl tests that read
`src/test/resources/shipsmooth.tosd` are updated to the main-resource location. One source, read by
both tests and payload staging, so copies can't drift.

#### How the value is injected — a `cli`-module constant, passed into `ConfigWriter`

The schema location is a **CLI / TOML concern** — `core` has zero TOML references, so the constant
does **not** belong in `core/Build.java` (that was the de-risk's mistake). Instead:

1. **`buildSrc/BuildEnv.kt`** — `schemaLocation(version, devStagedSchema)` derives the value:
   `PROD` → the `v<version>` pinned URL; `DEV` → the `file://` to the dev staged copy. *(done in
   de-risk; stays.)*
2. **A `cli`-module build constant** carries the value (CLI gets its own generated constant, mirroring
   how `core` generates `Build`). `build.env` at CLI-compile time selects dev-vs-prod.
3. **`ConfigWriter` takes `location` as a caller-provided value — no default.** When the caller
   provides a location, emit `[toml-schema]` with `version` + `location`; when it provides none,
   emit the `version` key only and **omit** `location` (the emitter skips a null `location`).
   Omitting is spec-valid (`location` is optional under `[toml-schema]`) and more honest than baking
   a possibly-wrong fallback URL into a user's config. The CLI wiring layer (`Store`/`Init`,
   hand-built, not Dagger) passes the variant constant; `ConfigWriter` itself stays dumb about
   build variants.
4. Revert the de-risk `core` changes (`Build.SCHEMA_LOCATION`, the `core/build.gradle.kts`
   `schema.location` expand) — superseded by the `cli` constant.
5. Adjust the conformance / integration tests (and any fixture) that assert the old `location`,
   including a variant that omits `location` and still validates.

#### Staging the file into payloads — shared `copySchema` producer

A `copySchema` producer in the shared/Claude assembly stages `shipsmooth.tosd` into the payload's
`schemas/` folder, wired into **both** `assembleClaudeDev` (→ `build-claude-dev/schemas/`) and
`assembleClaudeProd` (→ `build/schemas/`). Extend to codex/opencode/windows as those assemblies are
touched. The dev `file://` location must agree with the dev staged path.

**Prod publish — release-layout finding:** `PublishRelease.syncDistAndPublish` rebuilds `dist/` each
release by copying `SHIPPED_BUILD_SUBPATHS` out of `build/`, then pushes `releases` and the
`v<version>` tag at the same commit. `schemas/` was **not** in that list, so the prod URL would be a
dead link. Add `"schemas"` to `SHIPPED_BUILD_SUBPATHS` **(done in de-risk)** and stage
`build/schemas/shipsmooth.tosd` via `copySchema` in `assembleClaudeProd`. With both, the prod schema
is version-locked — re-copied from `build/` and captured by the `v<version>` tag on every cut.

Per-host note: prod publishes separate payloads (Claude `dist/`, Codex `dist-codex/`, Windows orphan
branch, OpenCode npm-only). The **prod URL** is the single canonical pointer all hosts share (only
physically published under Claude's `dist/`); the **dev** per-platform copies are for offline
file-resolution in each dev payload.

*Risk: Medium — moves a shipped resource out of test, adds a `cli` build constant, changes a public
`ConfigWriter` signature, adds a shared assembly producer, and touches release wiring.*

## Open questions

- When adding `toml-schema` version/URL to emitted config, should we bump it on every
  release or keep it stable? (Decision: defer — user indicated "not for now" on schema
  version compatibility.)
