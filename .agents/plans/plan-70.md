# Plan 70 — Fix the packaged launcher coordinate and the broken `plan tag` command

## Context

**Backlog feature (Local mode):** Release-packaging and workflow-CLI
correctness — the published runtime zip must launch on the current module
layout, and `shipsmooth plan tag` must actually create the correct version tag.
No external backlog tracker; recorded here per Core Invariant #3.

This plan covers two independent defects found together: (1) the packaged
runtime launcher's stale module coordinate, and (2) the `plan tag` command
failing to create any tag and miscomputing the first version as `v2`.

## Defect A — packaged launcher's stale module coordinate

The installed `runtime-0.3.13/bin/shipsmooth` launcher dies at startup with:

```
java.lang.module.FindException: Module io.bitken.ss not found
```

`0.3.12` runs; `0.3.13` does not. The jlink image's module set changed between
the two releases:

- **0.3.12** ships one module: `io.bitken.ss@0.3.12`.
- **0.3.13** ships two: `io.bitken.ss.cli@0.3.13` + `io.bitken.ss.core@0.3.13`.
  There is no module named `io.bitken.ss`.

But the packaged launcher still executes `-m io.bitken.ss/io.bitken.ss.cli.Shipsmooth`.
The entry point is otherwise correct — running the 0.3.13 image with the new
coordinate works:

```
runtime-0.3.13/runtime/bin/java -m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth --help   # OK
```

### Root cause

The module split landed in **plan 68** (`cli/pom.xml` comment: "plan 68, Task 1
… two-module"). Plan 68 updated the jlink `--launcher` arg and the SCC launcher
template in `cli/pom.xml` to `io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth`, but
it missed **`packaging/src/main/java/io/bitken/ss/dist/PackageRuntime.java`** —
the step that rewrites the launcher into its install-relative form for the
published zip. Both launcher builders there still emit the pre-split module:

- `PackageRuntime.java:107` (Windows `.cmd`): `-m io.bitken.ss/io.bitken.ss.cli.Shipsmooth`
- `PackageRuntime.java:120` (POSIX `shipsmooth`): same

The installed 0.3.13 launcher is byte-for-byte `PackageRuntime.buildPosixLauncher()`'s
output, confirming this is the producer.

### Why it escaped CI

`PackageRuntimeTest` asserts the launcher embeds the version string, the SCC dir,
and the launcher filename — but never the `-m` module/entry-point coordinate, and
never launches it. The `cli/pom.xml` jlink smoke tests run against the *SCC*
launcher (which plan 68 fixed), not the *PackageRuntime* launcher.

## Defect B — `plan tag` creates no tag and miscomputes the first version

`shipsmooth plan tag --plan N --kind version` fails for every plan:

```
$ shipsmooth plan tag --plan 70 --kind version
ERROR: failed to create tag plan-70-v2
```

Two bugs compound here, both in `core/.../gw/GitTags.java` and its wiring:

**B1 — wrong working directory (the dominant failure).** `GitTags` is the only
git-touching gateway that runs git in the JVM's inherited CWD. Every other
gateway is given the repo root explicitly: `ServicesModule.provideGitState`
passes `repoRoot` to `new GitState(repoRoot)` (which runs every command with
`.directory(workDir)`), as do `WorktreeService` and `WorkflowServiceImpl`. But
`provideGitTags()` is `new GitTags()` with no path, and `GitTags`'s
`ProcessBuilder("git", ...)` calls set no `.directory(...)`. When the CLI is
invoked from any CWD that isn't the git repo root, every `GitTags` git call
exits non-zero, so `createTag` returns false → "failed to create tag". This also
hits `--kind complete`/`--kind abandoned`, which skip the version math entirely
and still fail — proving the failure is in `createTag`, not the version
computation. `GitState` already exposes the correct pattern, including
`tagExistsLocally`/`tagExistsOnRemote`, which duplicate (and outdo) `GitTags`'s
local-only `tagExists`.

**B2 — first version computes as v2, not v1.** `getPlanVersion(N)` returns the
default `plan-N-v1` when no tag exists; `nextPlanVersion` then *unconditionally*
increments it, yielding `plan-N-v2` for the very first tag. The first version of
any plan should be `v1`. This is masked by B1 today (nothing gets created at all)
but is a real off-by-one. `PlanTagTest` never catches it because every test stubs
`nextPlanVersion` to a constant rather than exercising the real method.

## Goal

**Defect A:** Correct the module coordinate in both `PackageRuntime` launcher
builders, add a regression test pinning the coordinate to the image's main
module, and cut a corrected runtime release.

**Defect B:** Give `GitTags` the repo root so it runs git in the right directory,
fix the first-version off-by-one, and add tests that exercise the real
`GitTags`/`Tag` path (not a stubbed `nextPlanVersion`) so both bugs stay fixed.

## Non-goals / invariants

- The entry class `io.bitken.ss.cli.Shipsmooth` is unchanged — only the module
  name (`io.bitken.ss` → `io.bitken.ss.cli`) is wrong.
- `cli/pom.xml`'s jlink `--launcher` and SCC launcher templates are already
  correct and must not change.
- `devtools/scripts/package-tasks-java.sh` is a separate, older fossil — its
  launcher references the pre-*rename* module
  `com.github.pramodbiligiri.shipsmooth.tasks` and is not what built 0.3.13. It
  is out of scope here (track separately), unless trivially deletable.
- Tag naming/semantics are unchanged: iterations are `plan-N-vK` starting at
  `v1`; `complete`/`abandoned` are fixed names. Existing tags are never deleted.

## Tasks

### Task 1: Fix `plan tag` — repo-root CWD and first-version off-by-one [High]

Defect B. Give `GitTags` the repo root and run all its git commands in it
(mirror `GitState`: constructor `Path workDir`, `.directory(workDir.toFile())`
on every `ProcessBuilder`); update `ServicesModule.provideGitTags` to
`new GitTags(repoRoot)`. Fix the first-version computation so `nextPlanVersion`
returns `plan-N-v1` when no version tag exists (and `v{K+1}` when the highest is
`vK`). Prefer reusing `GitState`'s existing tag-existence checks over the
duplicate `GitTags.tagExists` if it consolidates cleanly.

Write failing tests first that exercise the **real** methods (not a stubbed
`nextPlanVersion`): in a temp git repo, `nextPlanVersion` returns `v1` with no
tags and `v3` when `v2` is the highest; `createTag` succeeds when the CLI's
process CWD is *not* the repo root (the condition that reproduces the live
failure); `--kind complete` creates `plan-N-complete`. Run to green.

High risk: the workflow's tagging contract (Core Invariants #1, #5) is currently
broken — no plan can be tagged — and this is a hard dependency for tagging this
very plan and all closeout. Touches DI wiring and the version algorithm.

### Task 2: Pin the launcher module coordinate, then fix it [High]

Defect A. Write a failing regression test in `PackageRuntimeTest` first: assert
the packaged POSIX launcher (`bin/shipsmooth`) and the Windows launcher
(`bin/shipsmooth.cmd`) both contain `-m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth`
and do **not** contain the bare `io.bitken.ss/` coordinate. Confirm it fails red
against the current code. Then fix `PackageRuntime.java:107` and `:120` to emit
`io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth`. Run to green.

High risk: this is the core correctness fix that determines whether the published
runtime launches at all; a wrong module name silently reships the broken zip.

### Task 3: Repackage and verify a launchable runtime [Medium]

*Depends-on: 2*

Rebuild the jlink image and the runtime zip from the fixed code (the existing
`PackageRuntime` packaging path), unpack it, and verify `bin/shipsmooth --help`
runs without `FindException` and that `--list-modules` shows `io.bitken.ss.cli`
matched by the launcher's `-m` target. Confirm the corrected launcher's `-m` line
reads `io.bitken.ss.cli/...`.

Medium risk: validates the end-to-end packaging output, not just the in-memory
string; surfaces any other coordinate drift between the image and the launcher.

### Task 4: Replace the broken installed 0.3.13 runtime [Low]

*Depends-on: 3*

The broken `~/.cache/shipsmooth/runtime-0.3.13` is a local install, not a repo
artefact. Once Task 3 produces a good zip, replace the local install (or cut the
corrected release) so `shipsmooth plan resume` works again on 0.3.13. Decide with
the human whether this ships as a re-cut 0.3.13 or a 0.3.14 (a published-but-broken
0.3.13 normally warrants a new version). No repo files change in this task.

Low risk: local install swap / release mechanics; no product code.

## Verification

- New `GitTags`/`Tag` tests run the real methods: `nextPlanVersion` → `v1` on an
  empty repo, `v{K+1}` otherwise; `createTag` succeeds when invoked from a CWD
  other than the repo root; `--kind complete`/`version` create tags. All red
  before the fix.
- `shipsmooth plan tag --plan 70 --kind version` creates `plan-70-v1` (not `v2`)
  and exits 0, run from the repo root and from an unrelated CWD.
- `PackageRuntimeTest` asserts both launchers carry
  `-m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth` and not the bare
  `io.bitken.ss/` coordinate; the test failed red before the fix.
- A runtime zip built from the fixed code unpacks to a `bin/shipsmooth` whose
  `--help` runs without `FindException`.
- `grep -rn "io.bitken.ss/io.bitken" packaging/` returns nothing.
- Full Java suite green (`mvn test`).
- A launchable runtime install/release replaces the broken 0.3.13 (post-merge).
