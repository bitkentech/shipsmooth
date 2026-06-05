# Plan 70 — Fix the packaged runtime launcher's stale module coordinate

## Context

**Backlog feature (Local mode):** Release-packaging correctness — the published
runtime zip must launch on the current module layout. No external backlog
tracker; recorded here per Core Invariant #3.

### The defect

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

### Goal

Correct the module coordinate in both `PackageRuntime` launcher builders, add a
regression test that pins the coordinate to the image's main module so a future
split can't silently reship the bug, and cut a corrected runtime release.

### Non-goals / invariants

- The entry class `io.bitken.ss.cli.Shipsmooth` is unchanged — only the module
  name (`io.bitken.ss` → `io.bitken.ss.cli`) is wrong.
- `cli/pom.xml`'s jlink `--launcher` and SCC launcher templates are already
  correct and must not change.
- `devtools/scripts/package-tasks-java.sh` is a separate, older fossil — its
  launcher references the pre-*rename* module
  `com.github.pramodbiligiri.shipsmooth.tasks` and is not what built 0.3.13. It
  is out of scope here (track separately), unless trivially deletable.

## Tasks

### Task 1: Pin the launcher module coordinate, then fix it [High]

Write a failing regression test in `PackageRuntimeTest` first: assert the packaged
POSIX launcher (`bin/shipsmooth`) and the Windows launcher (`bin/shipsmooth.cmd`)
both contain `-m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth` and do **not**
contain the bare `io.bitken.ss/` coordinate. Confirm it fails red against the
current code. Then fix `PackageRuntime.java:107` and `:120` to emit
`io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth`. Run to green.

High risk: this is the core correctness fix that determines whether the published
runtime launches at all; a wrong module name silently reships the broken zip.

### Task 2: Repackage and verify a launchable runtime [Medium]

*Depends-on: 1*

Rebuild the jlink image and the runtime zip from the fixed code (the existing
`PackageRuntime` packaging path), unpack it, and verify `bin/shipsmooth --help`
runs without `FindException` and that `--list-modules` shows `io.bitken.ss.cli`
matched by the launcher's `-m` target. Confirm the corrected launcher's `-m` line
reads `io.bitken.ss.cli/...`.

Medium risk: validates the end-to-end packaging output, not just the in-memory
string; surfaces any other coordinate drift between the image and the launcher.

### Task 3: Replace the broken installed 0.3.13 runtime [Low]

*Depends-on: 2*

The broken `~/.cache/shipsmooth/runtime-0.3.13` is a local install, not a repo
artefact. Once Task 2 produces a good zip, replace the local install (or cut the
corrected release) so `shipsmooth plan resume` works again on 0.3.13. Decide with
the human whether this ships as a re-cut 0.3.13 or a 0.3.14 (a published-but-broken
0.3.13 normally warrants a new version). No repo files change in this task.

Low risk: local install swap / release mechanics; no product code.

## Verification

- `PackageRuntimeTest` asserts both launchers carry
  `-m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth` and not the bare
  `io.bitken.ss/` coordinate; the test failed red before the fix.
- A runtime zip built from the fixed code unpacks to a `bin/shipsmooth` whose
  `--help` runs without `FindException`.
- `grep -rn "io.bitken.ss/io.bitken" packaging/` returns nothing.
- Full Java suite green (`mvn test`).
- A launchable runtime install/release replaces the broken 0.3.13 (post-merge).
