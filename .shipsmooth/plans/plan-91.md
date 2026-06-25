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

#### Decided shape

- The repo (`bitkentech/shipsmooth`) is **public**, so a raw GitHub URL resolves for anyone.
- Emit `location = "https://raw.githubusercontent.com/bitkentech/shipsmooth/releases/dist/schemas/shipsmooth.tosd"`
  (branch ref `releases`, not a per-version tag — acceptable since nothing fetches it and it must
  track the latest release).
- Publish the `.tosd` once, into the Claude `dist/` payload at `dist/schemas/shipsmooth.tosd`.
- The `location` must **not** point at `cli/src/test/resources/shipsmooth.tosd` (build-internal
  test path) and the file must **never** be written next to the user's `shipsmooth.toml`.

#### One URL for all hosts (release-layout finding)

`PublishRelease` publishes **separate per-host payloads** on the `releases` branch, not one shared
tree: Claude → `dist/`; Codex → `dist-codex/` (flat folder); Windows → orphan `releases-<version>`
branch; OpenCode → **npm only**, never on `releases`; Gemini assembled separately. Therefore the
`.tosd` is only physically published under **Claude's `dist/`**. Because `location` is documentary
(no per-host fetch), every host's `ConfigWriter` should reference the **same single URL** above —
do **not** stage a per-plugin offline copy; that only multiplies the sync surface for no benefit.

#### Auto-sync mechanism — and the gap to close

`PublishRelease.syncDistAndPublish` rebuilds `dist/` from scratch each release by copying a fixed
list `SHIPPED_BUILD_SUBPATHS = [".claude-plugin", "hooks", "dist", "skills"]` out of `build/`,
then commits + pushes `releases`. So anything in those dirs is republished in lockstep with zero
drift — but `schemas/` is **not** in that list, so the URL is a dead link until wired in.

Implementation steps:
1. Stage `cli/src/test/resources/shipsmooth.tosd` into the Claude prod build payload at
   `build/schemas/shipsmooth.tosd` (during `assembleClaudeProd`, the same way skills/hooks are
   assembled).
2. Add `"schemas"` to `SHIPPED_BUILD_SUBPATHS` in `PublishRelease` so the publish step copies it
   to `dist/schemas/` and pushes it. With both in place the schema is auto-synced and
   version-locked — re-copied from `build/` on every cut, so it can never drift from the release.
3. Update `ConfigWriter` to emit the raw `releases` URL as `location` in place of
   `./shipsmooth.tosd`, and adjust the conformance test / any fixture asserting the old value.

*Risk: Medium — touches release/build wiring (`PublishRelease`, Claude assembly) and a
user-visible config value; needs a publish step, not just a string change.*

## Open questions

- When adding `toml-schema` version/URL to emitted config, should we bump it on every
  release or keep it stable? (Decision: defer — user indicated "not for now" on schema
  version compatibility.)
