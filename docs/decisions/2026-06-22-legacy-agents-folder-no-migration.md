# Legacy `.agents/` Data Folder — No Back-Compat, No Migration (Guard Only)

**Date:** 2026-06-22
**Plan:** [plan-85](../../.agents/plans/plan-85.md) — Task 1
**Branch:** `t/85-shipsmooth-data-folder-naming-structure`
**Module:** `cli` (`io.bitken.ss.cli.conf`)

## Context

plan-85 renames shipsmooth's data folder `.agents/` → `.shipsmooth/` and flips the
default to external-by-default. Task 1 had to settle what happens to any existing
user or deployment that already has a `.agents/` data tree before this lands.

shipsmooth **is** published (a plugin marketplace, README install instructions, and
version tags through v0.3.25), so "there are no existing users" is not a free
assumption. However, an existing user's data lives in `.agents/` **inside their own
project repos** — directories this codebase can neither see at build time nor safely
move on their behalf.

## Decision

**No back-compat and no migration. A detection guard only.**

- The new code only ever looks for `.shipsmooth/`. It never reads, moves, copies, or
  transforms a `.agents/` tree.
- On the in-repo resolution paths, if a legacy `.agents/plans/` shipsmooth data tree
  is present, resolution **fails loudly** with an actionable error (names both the old
  and new folder and gives the `git mv .agents .shipsmooth` command) instead of
  silently treating the repo as a clean, unconfigured in-repo project.

The alternative — silently resolving such a repo as in-repo under the new `.shipsmooth/`
name — would strand the user's existing plan history under a folder nothing reads. That
is the precise failure this guard exists to prevent.

## Implementation

- `LegacyDataTreeGuard.check(repoRoot)` (new) — throws `StandaloneConfigException` when
  `repoRoot/.agents/plans/` is a directory.
- Wired into the two in-repo fallback paths of `ProjectDataStoreResolver.resolve()`
  (no config file; config present but no matching entry). A repo with an explicit
  standalone config entry is unaffected — that is the user's opt-out and any stray
  `.agents/` there is `Standalone.init()`'s concern.

### Detection heuristic: `.agents/plans/`, not bare `.agents/`

The guard keys on the shipsmooth-specific `.agents/plans/` **subdirectory**, not a bare
`.agents/` directory. In 2026 `.agents/` is converging on a convention for
human-authored agent *config* (skills, MCP servers). Tripping on bare `.agents/` would
produce false positives for users who have that config but never used shipsmooth's old
layout. Keying on `.agents/plans/` targets shipsmooth's own historical data tree
specifically. (Conservative by design; can be tightened later if a manifest marker —
plan-85 Task 6 — gives a more authoritative signal.)

## One-time manual step for affected users

If shipsmooth refuses to run with the legacy-folder error, rename the data tree by hand:

```sh
git mv .agents .shipsmooth      # or move it, preserving the plans/ subtree
```

then re-run. There is no automated path; this is intentional.

## Consequences

- A precedence interaction to formalise in **Task 4** (branch-table rewrite): a repo with
  *both* a malformed config entry (matched localPath, missing `stateDir`) *and* a legacy
  `.agents/` tree currently surfaces the config error first, because that check precedes
  the guard in `resolve()`. Acceptable (the config error is the more proximate fix), but
  Task 4 should make this ordering deliberate rather than incidental.
