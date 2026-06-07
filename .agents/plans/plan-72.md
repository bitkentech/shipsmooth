# Plan 72 — Maven teardown (remove Maven after Gradle release proves out)

## Context

plan-71 completed the Maven→Gradle migration *functionally*: all seven modules build under
Gradle, all five plugin payloads (claude-dev/prod, gemini-dev/prod, windows) assemble
byte-identical to Maven, the 5-platform jlink images + OpenJ9 SCC launcher work, the TS suite
runs in `check`, and the release path is Gradle-native (plan-71 Task 26 — `PublishRelease.java`
and the live `release.sh` / `release-gemini.sh` scripts all invoke `./gradlew`, no `mvn`).

What plan-71 deliberately did **not** do is delete the poms. The de-risking decision was: merge
plan-71 to `main` with **both** build systems present, cut a **real** release via the proven
Gradle path, and only then remove Maven. This plan is that teardown.

**Precondition for starting this plan:** a real release has been cut from `main` using the
Gradle path and verified good (the human runs the outward-facing release). Until that has
happened, do not begin Task 1.

Backlog feature: tracked in plan narrative only (Local mode), continuation of the build-migrate
work (`docs/proposals/build-migrate.md`). plan-71's deferred Task 17 is the seed of this plan.

## Objectives

1. Confirm full parity one final time, on a clean tree, across all five payloads + the
   `runtime-<ver>/` zip — Gradle vs Maven — while the poms still exist (last chance to diff).
2. Remove Maven entirely: the nine `pom.xml` files, the dead `package-tasks-java.sh`, and every
   `mvn` invocation in docs/CI/scripts (except historical `docs/proposals/*.md` narrative).
3. Leave `main` building and releasing cleanly with **no `pom.xml` present**.

## Scope

**In scope:**
- Final parity sign-off (all 5 payloads + runtime zip).
- Delete all **nine** real `pom.xml` files: root reactor, `skills/` aggregator, and the seven
  modules (`core`, `cli`, `skills/pkg`, `claude`, `gemini`, `packaging`, `devtools`).
- Remove the dead `devtools/scripts/package-tasks-java.sh` (references a non-existent `app`
  module; superseded by `packageRuntime_linux-x64`).
- Update `DEVELOPMENT.md` and `devtools/scripts/smoke-gemini.sh` to Gradle commands. (No CI to
  update — the project has no `.github/` / pipeline.)
- Tag the cutover commit.

**Out of scope:**
- Any change to build *behaviour* or payload *output* — this plan only removes Maven; the
  Gradle build is already the source of truth.
- `docs/proposals/*.md` — left as historical migration narrative (they intentionally describe
  the old Maven setup).
- Moving functionality between Java and scripts (same constraint as plan-71 Task 26).

## Key facts carried from plan-71

- `mvn` only runs cleanly **offline** (`-o`) from automation here; the populated local repo is
  `/opt/mvn/repository`. The sandbox blocks online `mvn` (it hangs). For the final parity diff,
  either run `mvn -o` or have the human run it via `!`.
- The real Maven baseline for a *full* payload is `mvn -o compile -P<profile>` into the default
  `build/` (the redirected `-Dbuild.outputDir` only runs the render phase, not the full
  reactor). Diff against `./gradlew assembleX -Pbuild.outputDir=<dir>`.
- `claude-prod` already verified byte-identical (plan-71 Task 26); the remaining four payloads
  were verified in plan-71 Tasks 21/22/24/25 when Maven was last runnable.

## Open questions

- Does `./gradlew build` from a totally clean checkout (fresh clone, no `~/.m2`, no poms) pass
  end-to-end, including jlink under `-PjlinkBuild`?

**Resolved:** there is **no CI** for this project — no `.github/` directory exists (confirmed
2026-06-07). The "update CI to Gradle" item is therefore a no-op; nothing references `mvn` from
a pipeline.

---

## Tasks

_Tasks are listed in risk-sorted execution order: 3 → 1 → 2. Task numbers are stable identities,
not sequence — Task 3 (High, independent) runs first; Task 1 (High) gates Task 2 (Medium)._

### Task 3: Fix runtime-install exec bits — `jspawnhelper` EACCES [High]

*Depends-on:*

**Independent of the Maven teardown; unblocks any subprocess-spawning CLI command on a freshly
installed release.**

**Problem (diagnosed 2026-06-07, strace-confirmed):** on a freshly installed release runtime,
every CLI command that shells out fails silently — git is never spawned. `plan branch` prints
the opaque `ERROR: failed to create branch`; `plan preflight`/`resume` degrade to misleading
defaults (empty git output read as "clean tree" / "tag not found").

**Root cause:** the install-side extractor in `skills/pkg/scripts/tasks/session-start.ts` calls
`new AdmZip(zipFile).extractAllTo(extractDir, true)` — **omitting AdmZip's third arg
`keepOriginalPermission`**, so every extracted file lands `0666 & ~umask` (`-rw-rw-rw-`), unix
modes dropped. A post-extract fixup then `chmod 0755`s only the launcher and `runtime/bin/*`
(lines ~89–96), so `runtime/bin/java` ends up executable but **`runtime/lib/jspawnhelper` does
not**. OpenJ9/Semeru routes every `ProcessBuilder.start()` through `jspawnhelper`; without `+x`
it fails with `EACCES (Permission denied)` and no child process is ever spawned. The producer
side is already correct — `PackageRuntime.java:79` stamps `Files.isExecutable(file) ? 0755 :
0644` into each zip entry; verified the released zip stores `jspawnhelper` as `-rwxr-xr-x`
(`zipinfo`). The bug is purely that the extractor ignores the stored mode.

**Not a regression / why it surfaced now:** the adm-zip extractor was introduced at plan-46
(2026-05-19) already without `keepOriginalPermission`; plan-50 (same day) hit this exact class
of bug for `runtime/bin/java` and patched it with the bin-only chmod allowlist — same root
cause, narrower miss. The defect stayed **latent** because nothing in the installed CLI spawned
a subprocess until `GitState` shipped at plan-67 (2026-06-03); the first `git`-shelling command
(`plan branch`/`preflight`/`resume`) turned the dormant exec-bit gap into a hard failure. The
manual `chmod +x` applied this session is a local unblock only, not a fix.

**Approach (spike-validated 2026-06-07):** pass `keepOriginalPermission = true` to
`extractAllTo` — i.e. `extractAllTo(extractDir, true, true)`. AdmZip 0.5.17 (the bundled
version) then `chmodSync`s each extracted file to the unix mode stored in its zip entry
(`adm-zip.js:767-768` → `util/utils.js:94` `fs.chmodSync(path, attr || 0o666)`). Because the
zip already carries correct modes for the whole tree, this fixes `runtime/lib/` and any future
executable location in one flag — and the bin-only chmod block (lines ~89–96) becomes
redundant (drop it; keep at most a minimal backstop if desired). Preferred over re-extending the
chmod allowlist, which has already failed once to anticipate a new executable path (`lib/`).
Caveat to check: AdmZip applies stored modes to directory entries too — `PackageRuntime` only
walks files (no explicit dir entries), so dirs are auto-created with AdmZip defaults (low risk),
but the test should assert the extracted tree is fully traversable, not just that the helper is
`+x`.

**Spike evidence** (`.agents/tmp/extract-probe.mjs`, real `shipsmooth-0.3.15-linux-x64.zip`,
bundled AdmZip; with `bin/java` chmod'd in both arms so the *only* variable is `lib/`):

| extract call | `runtime/lib/jspawnhelper` | `plan branch` (real git shell-out) |
|---|---|---|
| `extractAllTo(dir, true)` (current) | `0666`, not executable | `ERROR: failed to create branch` (exit 1) |
| `extractAllTo(dir, true, true)` (fix) | `0755`, executable | `Created branch` (exit 0) |

**Secondary (same task, smaller — split out if it balloons):** `GitState.runExitCode`
(`core/.../gw/GitState.java`) discards subprocess stderr, which is why the `EACCES` was
invisible and the diagnosis needed `strace`. Surface git's stderr on non-zero exit so
`createBranch`/preflight failures are self-diagnosing.

Acceptance: a fresh extract/install of a release zip leaves `runtime/lib/jspawnhelper`
executable (`0755`) with **no** post-extract chmod of `lib/`; `plan branch` succeeds end-to-end
through the installed launcher with no manual chmod; a `skills/pkg` test asserts the extracted
helper is executable (ideally: entry modes are honored and the tree is traversable).

### Task 1: Final full-parity sign-off (all 5 payloads + runtime zip) [High]

*Depends-on:*

On a clean tree, with the poms still present, build each payload **both** ways and diff:
- claude-dev, gemini-dev, claude-prod, gemini-prod, windows — `./gradlew assembleX` vs
  `mvn -o compile -P<profile>` into the default `build*/` tree.
- The `runtime-<ver>/` linux-x64 zip — Gradle `packageRuntime_linux-x64` vs the Maven jlink
  package path.

Record the diffs (empty or known noise: timestamps, the jq stamp which is now a Gradle no-op).
This is the last point Maven exists to diff against, so it is a hard gate before Task 2.

Acceptance: every payload parity-clean (or documented known-noise only); sign-off recorded in
a short note (e.g. `docs/observations/`).

### Task 2: Remove Maven + cutover docs/CI [Medium]

*Depends-on: 1*

**Only after Task 1 sign-off.** In a single cutover commit:
- Delete all nine `pom.xml` files (root + `skills/` aggregator + 7 modules).
- Delete `devtools/scripts/package-tasks-java.sh` (dead `app`-module script).
- Update `DEVELOPMENT.md` (build/release/version sections) and
  `devtools/scripts/smoke-gemini.sh` to Gradle commands.
- Leave `docs/proposals/*.md` untouched (historical record).

Acceptance: `grep -rn 'mvn ' .` finds nothing outside `docs/proposals/*.md`; `./gradlew build`
green from a clean checkout with **no `pom.xml` present**; `./gradlew :skills:pkg:check` runs
the TS tests. Tag the cutover commit.
