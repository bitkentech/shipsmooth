# exp/ — Experimental & Exploratory Work

**Everything in this directory is completely experimental. Nothing here is guaranteed to work.**

This is a scratch space for exploratory work that has **no shipping path** — it is not gated by
a feature flag, not compiled as part of the default build, not tested in CI, and not deployed to
users. At most, an entry here may have *opt-in* build wiring (explicitly invoked convenience
tasks); nothing in `exp/` ever runs as part of `./gradlew build`, `check`, or a release.

Treat anything here as provisional: it may be broken, half-finished, out of date, or abandoned at
any time, with no notice and no compatibility guarantees.

## Current contents

- `model/` — TLA+ formal-verification specs (`*.tla` / `*.cfg`). Checked with the TLC model
  checker by hand; the `*.out`, `*.tlc`, and `states/` run artifacts are gitignored.
- `rust/` — exploratory Rust port of the core plan/task logic (Cargo workspace; plan-102).
  Built by hand with cargo, or via the opt-in `:exp:rust:cargo{Build,Test,Clean}` Gradle
  tasks; `target/` build output is gitignored. See the "Rust port" section in
  `EXPERIMENTAL.md` at the repo root.

## What belongs here vs. elsewhere

- **Here (`exp/`):** work with no feature flag and no shipping path yet — at most opt-in
  convenience build tasks, never part of the default build.
- **Not here:** feature-flagged experimental *skills* (e.g. `skills/experimental/refine`,
  `skills/experimental/start-parallel`, `skills/experimental/start-tla`). Those are gated by the
  `experimental.enabled` flag but are fully wired into the build and shipped to opt-in users — see
  `EXPERIMENTAL.md` at the repo root.

Something graduates out of `exp/` once it earns a real module, a feature flag, or a shipping path.
