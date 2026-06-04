# exp/ — Experimental & Exploratory Work

**Everything in this directory is completely experimental. Nothing here is guaranteed to work.**

This is a scratch space for exploratory work that has **no build wiring** — it is not a Maven
module, not gated by a feature flag, and has no shipping path. It is not compiled as part of the
build, not tested in CI, and not deployed to users.

Treat anything here as provisional: it may be broken, half-finished, out of date, or abandoned at
any time, with no notice and no compatibility guarantees.

## Current contents

- `model/` — TLA+ formal-verification specs (`*.tla` / `*.cfg`). Checked with the TLC model
  checker by hand; the `*.out`, `*.tlc`, and `states/` run artifacts are gitignored.

## What belongs here vs. elsewhere

- **Here (`exp/`):** work with no Maven module, no feature flag, and no shipping path yet.
- **Not here:** feature-flagged experimental *skills* (e.g. `skills/experimental/refine`,
  `skills/experimental/start-parallel`, `skills/experimental/start-tla`). Those are gated by the
  `experimental.enabled` flag but are fully wired into the build and shipped to opt-in users — see
  `EXPERIMENTAL.md` at the repo root.

Something graduates out of `exp/` once it earns a real module, a feature flag, or a shipping path.
