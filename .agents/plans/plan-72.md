# Plan 72 — Runtime install fix + final Maven parity sign-off (pre-release)

> **Rescoped at v2 (2026-06-07):** Maven removal was **dropped from this plan** and moved to a
> new **plan-73**. The new sequence the human chose: (1) land the runtime-install fix + the
> final parity sign-off here, (2) **close plan-72, squash-merge to `main`, cut a patch release**
> via the Gradle path and verify it good, (3) **then** remove Maven in plan-73. Rationale:
> exercise the Gradle release path once more on a clean patch release — carrying the new
> jspawnhelper fix — while Maven still exists as a fallback, before burning that safety net.
> Original Task 2 (delete poms / docs cutover / tag) is retargeted to plan-73 below; it is
> deleted from this plan, not commented out.

## Context

plan-71 completed the Maven→Gradle migration *functionally*: all seven modules build under
Gradle, all five plugin payloads (claude-dev/prod, gemini-dev/prod, windows) assemble
byte-identical to Maven, the 5-platform jlink images + OpenJ9 SCC launcher work, the TS suite
runs in `check`, and the release path is Gradle-native (plan-71 Task 26 — `PublishRelease.java`
and the live `release.sh` / `release-gemini.sh` scripts all invoke `./gradlew`, no `mvn`).

The poms still exist (both build systems present on `main`). Before removing Maven, this plan
(a) fixes a release-install bug found in this cycle — the runtime ships
`runtime/lib/jspawnhelper` non-executable, breaking every subprocess-spawning CLI command on a
fresh install (see Task 3) — and (b) records the final Gradle-vs-Maven parity sign-off while
Maven is still here to diff against. Maven removal itself is plan-73, gated on a real release
of this plan's output proving good.

Backlog feature: tracked in plan narrative only (Local mode), continuation of the build-migrate
work (`docs/proposals/build-migrate.md`). plan-71's deferred Task 17 is the seed of plan-72/73.

## Objectives

1. Fix the runtime-install exec-bit bug so a freshly installed release runtime can spawn
   subprocesses (the gate for the release to be worth cutting).
2. Confirm full parity one final time across all five payloads + the `runtime-<ver>/` zip —
   Gradle vs Maven — while the poms still exist (last chance to diff).
3. Leave the branch ready to close, squash-merge, and release; Maven removal deferred to
   plan-73.

## Scope

**In scope:**
- Runtime-install exec-bit fix + the secondary git-stderr surfacing (Task 3).
- Final parity sign-off (all 5 payloads + runtime zip) (Task 1).

**Out of scope (moved to plan-73):**
- Deleting the nine `pom.xml` files, removing the dead `package-tasks-java.sh`, and the
  `DEVELOPMENT.md` / `smoke-gemini.sh` cutover-to-Gradle edits, and tagging the cutover.

**Out of scope (unchanged):**
- Any change to build *behaviour* or payload *output*.
- `docs/proposals/*.md` — left as historical migration narrative.
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

(None for plan-72. The clean-checkout-with-no-poms question moves to plan-73, where Maven is
actually removed. Confirmed this cycle: there is **no CI** — no `.github/` directory exists.)

---

## Tasks

_Tasks are listed in risk-sorted execution order: 3 → 1 → 4. Maven removal is plan-73, not a
task here. Task 3 (High) is the release-blocking install fix; Task 1 (High) is the parity
sign-off; Task 4 (Low) is build-hygiene found during cleanup. Tasks 3 and 1 are already done
(agent-coded); Task 4 was added at v3 after they completed._

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
This is the last point Maven exists to diff against (before plan-73 removes it).

Acceptance: every payload parity-clean (or documented known-noise only); sign-off recorded in
a short note (e.g. `docs/observations/`).

### Task 4: Give `claude`/`gemini` a `clean` task [Low]

*Depends-on:*

**Build hygiene found while cleaning artifacts after Tasks 3+1.** The `claude` and `gemini`
integration modules apply **no Gradle plugin** ("no java-conventions here — resource filtering
only"), so they have **no `clean` task**. The root `./gradlew clean` runs `:cli:clean`,
`:core:clean`, `:packaging:clean`, `:skills:pkg:clean` but cannot touch `claude/build` or
`gemini/build` — those dirs accumulate and can only be removed with `rm`. (`:claude:clean`
errors: "task 'clean' not found in project ':claude'".)

Fix: apply the `base` plugin to each module — it provides `clean` plus the lifecycle tasks
(`assembleX` already hook in) **without** a Java toolchain, the idiomatic Gradle answer for a
resource-only module. Purely additive; no behaviour or payload-output change.

Acceptance: `./gradlew :claude:clean :gemini:clean` succeeds and removes both `build/` dirs;
root `./gradlew clean` now sweeps them too (note: `buildSrc/build` is a separate included build
— still cleaned via `./gradlew -p buildSrc clean`, by design, not this task). No change to any
assembled payload.

---

_Maven removal (former Task 2) lives in **plan-73**: delete the nine poms + dead
`package-tasks-java.sh`, cut `DEVELOPMENT.md` / `smoke-gemini.sh` over to Gradle, tag the
cutover. **Precondition:** a patch release cut from `main` after plan-72 merges is verified
good. See `.agents/plans/plan-73.md`._
