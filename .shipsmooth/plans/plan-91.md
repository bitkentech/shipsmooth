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
is never installed there, so the reference is a dangling pointer. Figure out how to make the
schema actually reachable.

Design direction (from review):
- The `location` must **not** point at `cli/src/test/resources/shipsmooth.tosd` — that is a
  build-internal test path and leaks an implementation detail.
- Prefer a stable, published location: a `dist/shipsmooth.tosd` on the release branch,
  referenced by a pinned raw GitHub URL
  (`https://raw.githubusercontent.com/bitkentech/shipsmooth/<release-ref>/dist/shipsmooth.tosd`).
- The `.tosd` **may** ship inside the CLI bundle / hooks / scripts if an offline copy is wanted,
  but it must **never** be written next to the user's `shipsmooth.toml`.

Open sub-questions to resolve as part of this task:
- Wire a build/release step that stages `shipsmooth.tosd` into `dist/` (otherwise the URL is a
  dead link on first ship), or confirm `dist/` is populated manually.
- Pin the URL to a tag/commit (immutable, must bump per release) vs. a moving branch ref
  (simpler, semantics drift). Schema is documentary/test-time-validated, so a branch ref is
  likely acceptable — decide explicitly.

*Risk: Medium — touches release/build wiring and a user-visible config value; needs a decision
on pinning and a publish step, not just a string change.*

## Open questions

- When adding `toml-schema` version/URL to emitted config, should we bump it on every
  release or keep it stable? (Decision: defer — user indicated "not for now" on schema
  version compatibility.)
