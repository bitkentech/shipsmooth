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
- Update `DEVELOPMENT.md`, `devtools/scripts/smoke-gemini.sh`, and any CI to Gradle commands.
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
- Is there any CI workflow file (`.github/workflows/*`) still calling `mvn`? (plan-71 surveyed
  docs/scripts but CI should be re-checked at teardown time.)

---

## Tasks

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

### Task 2: Remove Maven + cutover docs/CI [High]

*Depends-on: 1*

**Only after Task 1 sign-off.** In a single cutover commit:
- Delete all nine `pom.xml` files (root + `skills/` aggregator + 7 modules).
- Delete `devtools/scripts/package-tasks-java.sh` (dead `app`-module script).
- Update `DEVELOPMENT.md` (build/release/version sections) and
  `devtools/scripts/smoke-gemini.sh` to Gradle commands.
- Update any `.github/workflows/*` (or other CI) `mvn` calls to `./gradlew`.
- Leave `docs/proposals/*.md` untouched (historical record).

Acceptance: `grep -rn 'mvn ' .` finds nothing outside `docs/proposals/*.md`; `./gradlew build`
green from a clean checkout with **no `pom.xml` present**; `./gradlew :skills:pkg:check` runs
the TS tests. Tag the cutover commit.
