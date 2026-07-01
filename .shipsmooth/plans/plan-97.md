# Plan 97 — Remove obsolete ledger references (and the dead subcommand guards)

## Context

The old parallel-execution subsystem (`claim` / `worker` / `integrate` /
`ledger`, plus a `ledger.jsonl` + object store) was **deleted at the source
level** in plan-82 and the surrounding cleanup in plan-84 (`bd16a5a`).
Verified: no `Ledger`/`ObjectStore` class exists in any `src/main`, no command
registers those names, and no code writes `ledger.jsonl` or an object store. The
feature is gone, not disabled — a deleted feature cannot "regress" on its own;
git history is what keeps it gone.

What remains are **textual references only**, and they fall into two groups that
must be treated differently:

**Genuinely dead (about removed subcommands — safe to delete):**
- `PlanServiceTest.mutationWritesNoLedgerSideChannel` — asserts no `ledger.jsonl`
  / object store is written. Guards code that no longer exists.
- `ProdSurfaceIntegrationTest` — its entire `REMOVED_NAMES`
  (`claim`/`worker`/`integrate`/`ledger`) are all deleted subcommands; it does
  not test the still-live `--enable-experimental` flag, so the whole file is dead.
- The four subcommand tokens `ledger`/`worker`/`claim`/`integrate` in
  `ReleaseGuard.EXPERIMENTAL_TOKENS`, and the
  `launcherGuardFailsWhenHelpLeaksExperimentalSubcommand` test that exercises
  them.
- Stale javadoc prose in `StateRoot.java` naming a ledger/object store in the
  data tree.
- `ledger/` + `*Ledger.java` example filenames in the experimental refine skill's
  package-structure template.

**Still load-bearing (must be preserved):**
- `ReleaseGuard`'s version-stamp check and `EXPERIMENTAL_BUILD = false` check —
  they caught the real 0.3.17 release defects.
- `ReleaseGuard`'s `--enable-experimental` flag-leak check + its
  `launcherGuardFailsWhenHelpLeaksExperimentalFlag` test — the
  `--enable-experimental` flag / `ExperimentalMode` / `Build.EXPERIMENTAL_BUILD`
  machinery is **still live** (wired into `CommandTree`, hidden in prod). There
  are simply no experimental subcommands behind it today. This guard stays.

So this plan removes everything dead while keeping the experimental-flag and
version guards intact.

Backlog feature: documentation/hygiene follow-through on the plan-82 / plan-84
ledger-subsystem removal. No change to shipped runtime behavior.

## Scope — exact edits

**Deletions (dead code):**
- `cli/src/test/java/io/bitken/ss/cli/ProdSurfaceIntegrationTest.java` — delete
  the whole file (all four guarded names are removed subcommands; nothing live
  left to assert).
- `core/src/test/java/io/bitken/ss/svc/plan/PlanServiceTest.java:76-90` — delete
  `mutationWritesNoLedgerSideChannel()`.
- `packaging/.../ReleaseGuard.java:28` — drop the four subcommand tokens from
  `EXPERIMENTAL_TOKENS`, leaving only `"--enable-experimental"`. Update the
  javadoc on line 26 accordingly.
- `packaging/.../ReleaseGuardTest.java:42-48` — delete
  `launcherGuardFailsWhenHelpLeaksExperimentalSubcommand` (no subcommand tokens
  remain to trip it). Keep `launcherGuardFailsWhenHelpLeaksExperimentalFlag`.

**Prose / example fixes:**
- `core/.../conf/StateRoot.java:10` — rewrite the data-tree description to name
  only what the tree holds (plan files + task state); drop "ledger, object store".
- `skills/experimental/refine/rules/package-structure.jte.md` (lines 19, 21, 29,
  45, 46, 49) — replace the `ledger/` package and `EventLedger.java` /
  `IntegrationLedger.java` / `LedgerService.java` example filenames with neutral
  example names, preserving the package-structure lesson the tree illustrates.

## Non-goals

- Not touching `ReleaseGuard`'s version / `EXPERIMENTAL_BUILD` / `--enable-experimental`
  guards — those are live.
- No change to shipped runtime behavior.
- Not retiring the broader feature-backlog / `<backlog-issue>` concept (tracked
  separately).

## Tasks

### Task 1: Delete the dead ledger/subcommand guards and fix stale prose [Low]

All Java-side removals in one slice, since they are interdependent (the
`ReleaseGuard` token removal and the subcommand-leak test removal must land
together, and the two dead test guards go with them):

- Delete `ProdSurfaceIntegrationTest.java`.
- Delete `PlanServiceTest.mutationWritesNoLedgerSideChannel()`.
- Trim `ReleaseGuard.EXPERIMENTAL_TOKENS` to `"--enable-experimental"` only;
  update its javadoc.
- Delete `ReleaseGuardTest.launcherGuardFailsWhenHelpLeaksExperimentalSubcommand`.
- Rewrite the `StateRoot.java` data-tree javadoc.

Verify: `./gradlew :packaging:test :cli:test :core:test` is green, and the
retained `ReleaseGuard` flag/version tests still pass (the live guards are
untouched).

### Task 2: Scrub ledger from the experimental package-structure example [Low]

Replace the `ledger/` package and `*Ledger.java` example filenames in
`skills/experimental/refine/rules/package-structure.jte.md` with neutral example
names, keeping the layout lesson intact. Verify the skills template still
renders/builds.
