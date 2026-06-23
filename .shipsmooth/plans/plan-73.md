# Plan 73 — Maven teardown (remove Maven after the post-plan-72 release proves out)

## Context

This plan removes Maven from the repo. It was split out of **plan-72** (rescoped at its v2): the
human's chosen sequence is to land the runtime-install fix + final parity sign-off in plan-72,
**close plan-72, squash-merge to `main`, cut a patch release** via the Gradle path and verify it
good — exercising the release path once more (carrying the new jspawnhelper fix) while Maven
still exists as a fallback — and **then** remove Maven here.

By the time this plan starts, the Gradle→Maven parity is already signed off (plan-72 Task 1,
`docs/observations/2026-06-07-build-migrate-parity-signoff.md`): all five payloads + the
linux-x64 runtime zip verified identical modulo known-noise (the version stamp, which only
exists because the poms are pinned at an older version than `gradle.properties` — and which
disappears the moment the poms are deleted here).

**Precondition for starting this plan:** a real patch release has been cut from `main` (after
plan-72 merged) using the Gradle path and verified good (the human runs the outward-facing
release). Until that has happened, do not begin Task 1.

Backlog feature: continuation of the build-migrate work (`docs/proposals/build-migrate.md`);
plan-71's deferred Task 17 is the original seed.

## Objectives

1. Remove Maven entirely: the nine `pom.xml` files, the dead `package-tasks-java.sh`, and every
   `mvn` invocation in docs/scripts (except historical `docs/proposals/*.md` narrative).
2. Leave `main` building and releasing cleanly with **no `pom.xml` present**.

## Scope

**In scope:**
- Delete all **nine** real `pom.xml` files: root reactor, `skills/` aggregator, and the seven
  modules (`core`, `cli`, `skills/pkg`, `claude`, `gemini`, `packaging`, `devtools`).
- Remove the dead `devtools/scripts/package-tasks-java.sh` (references a non-existent `app`
  module; superseded by `packageRuntime_linux-x64`).
- Update `DEVELOPMENT.md` and `devtools/scripts/smoke-gemini.sh` to Gradle commands. (No CI to
  update — the project has no `.github/` / pipeline, confirmed 2026-06-07.)
- Tag the cutover commit.

**Out of scope:**
- Any change to build *behaviour* or payload *output* — Gradle is already the source of truth;
  this plan only removes Maven.
- `docs/proposals/*.md` — left as historical migration narrative (they intentionally describe
  the old Maven setup).
- Moving functionality between Java and scripts (same constraint as plan-71 Task 26).

## Open questions

- Does `./gradlew build` from a totally clean checkout (fresh clone, no `~/.m2`, no poms) pass
  end-to-end, including jlink under `-PjlinkBuild`?

## Key facts carried forward

- Parity is already signed off (plan-72 Task 1). The only Gradle-vs-Maven diff was the version
  stamp (poms pinned below `gradle.properties`); deleting the poms removes that source entirely,
  so post-deletion there is nothing left to diverge.
- `gradle.properties` `plugin.version` is the single source of truth for the version once the
  poms are gone (plan-71 Task 26 already moved the release version bump there).

---

## Tasks

### Task 1: Remove Maven + cutover docs [Medium]

*Depends-on:*

**Only after the post-plan-72 release is verified good.** In a single cutover commit:
- Delete all nine `pom.xml` files (root + `skills/` aggregator + 7 modules).
- Delete `devtools/scripts/package-tasks-java.sh` (dead `app`-module script).
- Update `DEVELOPMENT.md` (build/release/version sections) and
  `devtools/scripts/smoke-gemini.sh` to Gradle commands.
- Leave `docs/proposals/*.md` untouched (historical record).

Acceptance: `grep -rn 'mvn ' .` finds nothing outside `docs/proposals/*.md`; `./gradlew build`
green from a clean checkout with **no `pom.xml` present**; `./gradlew :skills:pkg:check` runs
the TS tests. Tag the cutover commit.
