# plan-104 — Move rust code to exp/rust (experimental) folder

## Context

The plan-102 Rust migration exploration left a Cargo workspace at top-level
`rust/` (`crates/ss-core`, `crates/ss-cli`, `fixtures/`, `parity/`). The
exploration got a GO verdict but the code is still experimental — it has no
shipping path and is not part of the Gradle build. The repo already has a home
for exactly this kind of work: `exp/` (currently holding only `exp/model/`, the
TLA+ specs).

This plan:

1. Moves `rust/` → `exp/rust/` to signal its experimental status.
2. Confirms Rust build output stays gitignored at the new location.
3. Explores wiring cargo build commands into Gradle via a
   `build.gradle.kts` inside `exp/rust`, and documents the result in
   `EXPERIMENTAL.md`.

### Findings from repo inspection

- `rust/.gitignore` (containing `target/`) travels with the move, and the root
  `.gitignore` already has `target/` + `**/target/` patterns — so
  `exp/rust/target/` is covered twice over. Task 3 is verification, not new work.
- Path references to `rust/...` exist in: Rust doc-comments
  (`ss-core/src/{plan/summary.rs, plan/markdown.rs, model/layout.rs, model/mod.rs}`,
  `ss-core/tests/golden_roundtrip.rs`), and `docs/rust-migration/{00-overview,01-core}.md`.
  Historical plan files (`plan-102.md`) are left untouched.
- **Doc contradiction to resolve:** `exp/README.md` defines `exp/` as work with
  "**no build wiring** — not compiled as part of the build, not tested in CI".
  Adding a `build.gradle.kts` under `exp/rust` breaks that definition unless the
  prose is updated. Resolution: the wiring is *opt-in convenience tasks only* —
  cargo is never invoked by the default build, `check`, or any release path —
  and `exp/README.md` is updated to say exactly that.
- The Rust toolchain lives under `/opt` (`CARGO_HOME=/opt/cargo`,
  `RUSTUP_HOME=/opt/installers/rustup`) and `/opt/cargo/bin` is **not** on the
  default `PATH`. The Gradle wiring must locate cargo robustly (env var /
  `cargo.home` property / PATH probe) and **degrade gracefully** (skip with a
  clear message, not fail) when cargo is absent, so the main build stays green
  on machines without a Rust toolchain.
- A detached worktree at `/opt/workspace/ss-rust` (cd36e18, plan-102 era) points
  at the old layout; it is pinned to an old commit so the move does not affect
  it. No action needed.

### Design sketch for the Gradle wiring (Task 2)

- `settings.gradle.kts`: `include("exp:rust")` (project dir `exp/rust`).
- `exp/rust/build.gradle.kts`: plain project (no java plugin) exposing
  `Exec`-based tasks: `cargoBuild`, `cargoTest`, `cargoClean` (working dir =
  project dir, so Cargo workspace resolution is untouched). Cargo binary
  resolved from `CARGO_HOME`, then a `cargo.home`/`org.gradle.project` property,
  then `PATH`; tasks are `onlyIf { cargoFound }` with a doFirst warning
  otherwise.
- Nothing attaches these tasks to `build`/`check`/`assemble` lifecycles — they
  run only when invoked explicitly (`./gradlew :exp:rust:cargoBuild`).

### Verification approach (TDD caveat)

This is a file move + build config + docs plan — per the skill's invariant
carve-out, most of it is not unit-TDD-able. Verification per task:

- Task 1: `cargo test` (workspace) passes from `exp/rust/` after the move;
  `grep -rn 'rust/'` shows no stale non-historical references.
- Task 2: `./gradlew :exp:rust:cargoBuild` builds; a bare `./gradlew build`
  still succeeds **without** cargo on PATH (graceful-skip check).
- Task 3: `git check-ignore exp/rust/target` passes; `git status` clean after a
  cargo build.
- Task 4: docs review only.

## Tasks

### Task 1: Move rust/ to exp/rust/ and fix path references [Low]

`git mv rust exp/rust`. Update the `rust/...` path mentions in the moved Rust
doc-comments/tests and in `docs/rust-migration/00-overview.md` +
`01-core.md` to `exp/rust/...`. Leave historical plan files untouched. Verify
the Cargo workspace still builds and tests green from the new location
(`cargo build && cargo test` in `exp/rust/`).

### Task 2: Gradle cargo tasks via exp/rust/build.gradle.kts [Medium]

*Depends-on: 1*

Add `include("exp:rust")` to `settings.gradle.kts` and create
`exp/rust/build.gradle.kts` per the design sketch: `cargoBuild` / `cargoTest` /
`cargoClean` Exec tasks, cargo located via `CARGO_HOME` → project property →
`PATH`, graceful skip when absent, and no attachment to default build
lifecycles. Verify both directions: tasks work with the `/opt/cargo` toolchain,
and `./gradlew build` is unaffected without cargo.

### Task 3: Verify Rust build output is gitignored at the new path [Low]

*Depends-on: 1*

Confirm `exp/rust/.gitignore` moved with the tree and that
`git check-ignore exp/rust/target` passes; run a cargo build and confirm
`git status` stays clean. Add an explicit root-`.gitignore` entry only if the
existing `target/` / `**/target/` patterns somehow miss it.

### Task 4: Document in EXPERIMENTAL.md and reconcile exp/README.md [Low]

*Depends-on: 2*

Add a "Rust port (exploratory)" section to `EXPERIMENTAL.md`: what lives in
`exp/rust`, the plan-102 GO verdict/footprint numbers, and the
`:exp:rust:cargo*` Gradle tasks. Update `exp/README.md`'s "no build wiring"
definition and "Current contents" list to admit opt-in convenience wiring that
never runs in the default build or CI.
