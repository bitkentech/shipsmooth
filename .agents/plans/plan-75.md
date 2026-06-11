# Plan 75 — Prod release leaks experimental surface + stale build constants

## Context

The released `runtime-0.3.17` CLI is wrong in three user-visible ways:

1. `--help` shows `--enable-experimental` — prod builds must hide it.
2. `--help` lists the `ledger` subcommand — prod builds should not expose it.
3. `--version` prints **0.3.16** — the 0.3.17 release shipped a binary stamped
   with the previous version.

Investigation found two build defects plus one deliberate-but-wrong design
decision:

### Defect 1 — `generateBuildConstants` has untracked inputs (root cause of 1 & 3)

`core/build.gradle.kts` generates `io.bitken.ss.Build` (the compile-time
constants `EXPERIMENTAL_BUILD` and `VERSION`) by expanding a template with the
`experimental.enabled` and `plugin.version` Gradle properties. The generator is
a `Copy` task whose up-to-date check only watches the template *file* — the
`expand()` values are invisible to Gradle. Once generated, the file is never
regenerated when either property changes.

**Proven:** with `plugin.version=0.3.17` in `gradle.properties` and
`-Pexperimental.enabled=false` on the command line, `:core:generateBuildConstants`
reports `UP-TO-DATE` and leaves `EXPERIMENTAL_BUILD = true, VERSION = "0.3.16"`
on disk. That stale file is exactly what the 0.3.17 release jlink image shipped.

### Defect 2 — the release path never forces `experimental.enabled=false`

The Maven `prod`/`windows` profiles set `<experimental.enabled>false</experimental.enabled>`;
that override was lost in the plan-71/73 Gradle migration. Today:

- `gradle.properties` defaults `experimental.enabled=true` (correct for dev).
- `PublishRelease.jlinkBuildCommand()` invokes the four `:cli:image_*` tasks
  with **no** `-Pexperimental.enabled=false`.
- The `packaging` `packageRuntime_*` / `stageImage_*` tasks likewise inherit the
  default.

So even with Defect 1 fixed, a release built via the documented
`./gradlew publishRelease ...` flow ships `EXPERIMENTAL_BUILD = true`.

### Design change — `ledger` must be experimental

`Ledger` deliberately does not implement `FeatureFlags` (only its `record-*` /
`watch` leaves are gated on runtime experimental mode), so the `ledger` group
always registers in `CommandTree`. The base `start` SKILL.md also documents
`ledger list` / `ledger verify`. Decision (this plan): the whole `ledger`
subcommand becomes experimental, and per the no-experimental-leakage rule the
base skill text must drop every ledger reference (dev skill keeps them).

### Why no test caught this

`ShipsmoothTest`'s prod/dev help assertion branches on `Build.EXPERIMENTAL_BUILD`
itself — in a dev build it only ever exercises the dev arm, so CI cannot catch a
prod leak. `ValidateRelease` checks the assembled payloads but never inspects
the packaged binary. Nothing asserted the launcher's `--version` matches the
release version.

### Backlog feature

Parent feature (permanent): **"Maven→Gradle build migration & dev-loop
tooling"** — same line of work as plans 71/72/73/74. This plan fixes prod/dev
variant correctness regressions introduced by that migration. (No external
tracker; recorded here per Local mode, Core Invariant #3.)

### Constraints / invariants

- **Dev and prod share the `image_*` tasks.** The dev loop (`devBuild`) wants
  `experimental.enabled=true`; the release wants `false`. The property must stay
  a per-invocation input (and, after Defect 1's fix, switching it must re-run
  the generator and recompile core).
- **Never run `publishRelease` autonomously** — it publishes outward. The human
  cuts the release.
- **0.3.17 is burned.** Fix ships as **0.3.18** (bump patch, never re-cut a
  released version).
- **Cross-platform binaries can't be executed on this host.** Any binary-level
  guard can only exec the linux-x64 launcher; other platforms need a
  source-of-truth check (e.g. the generated `Build.java` contents at build
  time).
- **Skill payload variants:** base (`start`) and dev (`start-dev`) render from
  shared JTE templates in `skills/`; ledger references must be conditioned on
  the experimental flag, not deleted from the dev variant.

### Testing strategy

Mixed plan. Gradle wiring tasks (1, 2) are verified by observable build
behavior (re-run-on-property-change, generated file contents) — not
line-coverage TDD. Java changes (Tasks 3, 5) follow normal TDD against the
existing cli/packaging test suites. Skill template changes (Task 4) are covered
by the existing `TargetIntegrationTest`-style rendered-output assertions.
Coverage bar: parity for touched modules (migration-era code), 95% for net-new
code only.

## Tasks

### Task 1: Track `expand()` values as inputs of `generateBuildConstants` + harden its defaults [Low]

Three changes to the `generateBuildConstants` wiring in `core/build.gradle.kts`,
all in the same block (lines 42–55):

1. **Track the inputs.** Add `inputs.property("experimentalEnabled", experimentalEnabled)`
   and `inputs.property("pluginVersion", pluginVersion)` to the task so a change
   to either value invalidates the up-to-date / build-cache key and regenerates
   `Build.java` (root-cause fix for the stale-constants defect). `Boolean` and
   `String` are both `Serializable`, so the simple-value `inputs.property` form is
   the canonical idiom; both vals are resolved eagerly at line 42–43, so the same
   resolved values feed both the fingerprint and `expand()` — no divergence path.

2. **Make the experimental default safe.** Flip the absent-property fallback from
   `?: true` to `?: false` on line 42. This brings `core` into line with the rest
   of the build, which already defaults experimental to `false`
   (`skills/pkg/.../Target.java` uses `System.getProperty("experimental.enabled", "false")`).
   Dev is unaffected: `gradle.properties` explicitly sets `experimental.enabled=true`,
   so the dev loop reads the properties file, never the code default. Only the
   property-*absent* edge case changes — and it changes to the safe prod value.

3. **Fail loud on a missing version.** Replace the stale-literal fallback
   `?: "0.3.14"` on line 43 with a configuration-time `throw GradleException(...)`
   when `plugin.version` is unset. A release stamping a wrong/old version is the
   exact failure class this plan kills; in the real build `gradle.properties`
   always supplies it, so this only fires on a genuine misconfiguration — and then
   it should stop the build, not silently stamp `0.3.14`. Configuration-time
   (not `doFirst`) so it fails fast before any task runs.

Low risk (small, well-understood Gradle idioms) but listed first as a hard
technical dependency of Task 2: without change 1, no release-path property
override has any effect on an incremental build.

Verify:
- `./gradlew :core:generateBuildConstants -Pexperimental.enabled=false`
  regenerates `Build.java` with `EXPERIMENTAL_BUILD = false`; flipping back
  regenerates again; same for a `plugin.version` change; unchanged properties
  stay `UP-TO-DATE`.
- A normal build (`gradle.properties` present) still defaults experimental
  correctly and stamps the configured version.
- Invoking with `plugin.version` removed fails at configuration with the
  GradleException message (not a silent `0.3.14` stamp).

### Task 2: Bake prod constants into the release build path [Medium]

*Depends-on: 1*

Restore the Maven prod-profile behavior: every release-side Gradle invocation
that compiles core must pass `-Pexperimental.enabled=false` (the version comes
from `gradle.properties`, already bumped by `PublishRelease` before building).

Touch points:

- `PublishRelease.jlinkBuildCommand()` — add the property to the `image_*`
  invocation (and audit `assembleProdCommand` / `assembleWindowsCommand`: the
  rendered payloads embed experimental-conditioned skill text and must also be
  prod).
- `packaging/build.gradle.kts` `packageRuntime_*` — document or enforce that a
  manually-invoked package run uses the prod flag (decide: hard-wire vs. doc).

Medium risk: the right *mechanism* (flag on the spawned Gradle command vs.
hard-wiring in the packaging tasks vs. a dedicated prod variant of the image
tasks) is a design decision with cross-module consequences.

### Task 3: Gate the `ledger` subcommand behind experimental mode [Low]

*Depends-on: none (but must land before Task 4 — the base skill may not
reference a command that prod no longer exposes)*

Make `Ledger` implement `FeatureFlags` (`isExperimental() = true`) so
`CommandTree` only registers it when `--enable-experimental` is passed. Drop the
now-redundant internal `mode.enabled()` split inside `Ledger`'s constructor —
once the whole group is gated, the `record-*`/`watch` leaves no longer need
their own gate (decide during implementation; keep if defense-in-depth is
preferred). Update `ShipsmoothTest`/integration tests that assume `ledger` is
always present.

### Task 4: Remove ledger references from the base `start` skill [Medium]

*Depends-on: 3*

The base skill currently documents `ledger list` / `ledger verify` and the
ledger/objects mechanics in the `[Local]` execution section. Condition those
template fragments on `experimental.enabled` so the base (`start`) payload has
zero ledger references while `start-dev` keeps them. Audit the full rendered
base payload (all variants: claude prod, gemini, windows) for any other
experimental leakage while in there.

Medium risk: JTE template conditioning across 5 render variants is where
plan-71-era regressions have repeatedly hidden.

### Task 5: Release-time binary guard [Medium]

*Depends-on: 1, 2*

Make the release fail loudly if the packaged artifacts are wrong:

- Exec the **linux-x64** staged launcher: assert `--version` equals the release
  version and `--help` contains neither `--enable-experimental` nor any
  experimental subcommand name (`ledger`, `worker`, `claim`, `integrate`).
- For non-executable platforms: assert the generated `Build.java` (single
  shared source for all images) has `EXPERIMENTAL_BUILD = false` and the right
  version before any `image_*` task packages it.

Wire into `ValidateRelease` or as a `doLast` guard on the release path —
placement is part of the task. This guard would have caught both shipped
defects.

### Task 6: Docs, e2e verification, 0.3.18 release prep [Low]

*Depends-on: 1, 2, 3, 4, 5*

- Update release docs (`docs/`, README pointers) to state the prod flag is
  baked in — no manual `-P` needed.
- e2e: clean-tree dev build (`devBuild`) still produces an experimental-enabled
  dev runtime; simulated release build produces a prod runtime whose `--version`
  is correct and whose help shows no experimental surface.
- Hand the human a ready-to-run `publishRelease` line for **0.3.18** (human
  executes; agent never runs it).
