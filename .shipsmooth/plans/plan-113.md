# Plan 113 — Fold the `shipsmooth-claude` image build into this repo as a `docker/` module

## Context

**Backlog feature:** consolidate the `shipsmooth-claude` image build into the main
repo (local backlog; no external issue). Continues the Docker distribution channel
introduced in plan-112 (`DOCKER.md` + the README "Docker (Claude Code)" method).

The `bitkentech/shipsmooth-claude` Docker image (Ubuntu + Node + Claude Code +
the pre-installed shipsmooth plugin) is currently built from a **separate private
repo**, `pramodbiligiri/cc-setup`. That repo holds two unrelated things:

1. **The image build** — `Dockerfile` + a single-module Gradle project
   (`io.bitken.ss.docker`: `ResolveVersions`, `BuildAndPushImage`, `ImagePlan`,
   `ValidateLabels`, `CommandRunner`/`ProcessCommandRunner`) with its own tests,
   plus `test/smoke.sh` and maintainer docs (`README.md`, `DEVELOPMENT.md`).
2. **Shared-host / hosting notes** — `server-notes*.txt`, `connect-*.sh`,
   `login-options.md`, `statusline.sh`: notes on operating a specific cloud box
   as `cloud.shipsmooth.net`. These contain real host addresses / SSH endpoints.

This plan moves **only group 1** into this (public) repo as a top-level `docker/`
Gradle module. Group 2 stays in `cc-setup` (which can be renamed to something
like `shipsmooth-cloud` afterwards — out of scope here).

### Why fold it in

- **Kills the version-pin drift.** `cc-setup` hand-copies `shipsmoothVersion` into
  its own `gradle.properties` and bumps it on release. In-repo, the module reads
  the root `plugin.version` — the same single source of truth every other module
  uses (`packaging/build.gradle.kts` already does `findProperty("plugin.version")`).
- **Discoverability + ownership.** The image definition sits next to the plugin it
  ships; `org.opencontainers.image.source` stops pointing at a private repo;
  issues route to one place.
- **Release consistency.** `DEVELOPMENT.md` already documents the Gemini / Windows
  / OpenCode release paths; the Docker image gets a peer section.

### Why a flat copy, not `git subtree`

`git subtree add --prefix=docker` imports **the entire `cc-setup` commit history**
into this repo — including the commits that added the hosting notes with real host
addresses and SSH endpoints. This repo is public. Therefore: **flat copy the
wanted files only, in a single new commit.** No history import. Provenance is
recorded in the commit message (source repo + commit SHA), not in git ancestry.

### Why top-level `docker/`, not `packaging/docker`

`packaging` is specifically the `publishRelease` orchestration (`io.bitken.ss.dist`,
depends on `:plugin-model`). The image tooling depends on nothing internal — it is
a downstream *consumer* of the **published** plugin (`claude plugin install
shipsmooth` against the `bitkentech` marketplace), not part of the build graph. A
`docker/` peer of `packaging/` models that correctly.

### Known wrinkle — `user.dir` (Task 1 must handle this)

`BuildAndPushImage` derives the Dockerfile path, the build context, and the
rendered `build/overview.md` path from `System.getProperty("user.dir")`. Run from
the repo root via `./gradlew :docker:buildImage`, that would resolve to the repo
root, not `docker/`. Fix: set `workingDir = layout.projectDirectory.asFile` on the
`JavaExec` task registrations so `user.dir` is the module dir. Add a
`docker/.dockerignore` (at least `src/`, `build/`, `.gradle/`) so the build
context stays small — the Dockerfile `COPY`s nothing from context today, but the
whole module dir would otherwise be sent to the daemon.

### The false-expectation note (Task 3 documents it)

A `docker/` image built from a feature branch still installs the **last published**
plugin from the marketplace, not the working tree. Co-location invites the
assumption that `buildImage` tests your branch — it does not. One sentence in
`docker/README.md` must say so.

### Source material (verified 2026-09-02, `cc-setup` @ `7c8dcd2`)

| Source (`cc-setup/`) | Destination (`docker/`) | Notes |
|---|---|---|
| `Dockerfile` | `Dockerfile` | change `image.source` label → `https://github.com/bitkentech/shipsmooth` |
| `src/main/java/io/bitken/ss/docker/*.java` (6 files) | same path under `docker/` | unchanged |
| `src/test/java/io/bitken/ss/docker/*.java` (4 files) | same path under `docker/` | unchanged |
| `src/test/resources/npm-registry-claude-code.json` | same | unchanged |
| `test/smoke.sh` | `docker/smoke.sh` | adjust `REPO_ROOT` (was `dirname/..` from `test/`; now the module dir itself) |
| `build.gradle.kts` (docker-tooling half) | `docker/build.gradle.kts` | rewrite on `shipsmooth.java-conventions`; port the 4 task registrations; `workingDir` fix |
| `DEVELOPMENT.md` | folded into `docker/README.md` | maintainer build/publish guide |
| `README.md` (end-user half) | — | already covered by repo-root `DOCKER.md` (plan-112); `docker/README.md` links to it |
| `settings.gradle.kts` | — | new `rootProject.name` line n/a; this repo's `settings.gradle.kts` gets `include("docker")` |
| `gradlew*`, `gradle/`, `.gradle/`, `build/` | — | use this repo's wrapper |
| `server-notes*.txt`, `connect-*.sh`, `login-options.md`, `statusline.sh`, `.shipsmooth/` | — | **not moved** — stay in `cc-setup` |

### Toolchain reconciliation (Task 1)

`cc-setup` used jackson-databind `2.18.2` and JUnit `5.11.4`; `shipsmooth.java-conventions`
gives JUnit `5.10.2` and siblings use jackson `2.17.2`. The `docker/` module should
adopt the convention JUnit and jackson `2.17.2` (match `:cli` / `:packaging`)
unless a test genuinely needs the newer line. `options.release = 21` and UTF-8
come from the convention plugin. No `module-info.java` (matches `:packaging`,
`:skills:pkg`); the convention's `modularity.inferModulePath.set(false)` covers it.

### Coverage

There is **no enforced coverage gate** in the build (jacoco produces reports only;
no `violationRules`). Net-new *logic* in this plan is essentially zero — it is a
file move plus Gradle wiring. `BuildAndPushImage`'s HTTP/push paths are
integration-tested by `smoke.sh` (a real `docker build` + `docker run`), exactly
as `cc-setup` treats them; the ported unit tests keep their existing coverage.
`:docker` should **not** be added to any aggregate coverage report. Confirm the
threshold expectation at Step 0 given there is no TDD-able net-new logic.

## Verification (end to end)

- `./gradlew :docker:test` green (the 4 ported test classes).
- `./gradlew :docker:resolveVersions` prints `claude-code=…`, `shipsmooth=<plugin.version>`,
  `compound-tag=claude-…-ss-<plugin.version>` — the `shipsmooth=` value equals the
  root `gradle.properties` `plugin.version`, with nothing pinned in `docker/`.
- `./gradlew build` (whole repo) still green — the new module compiles and tests
  in the reactor.
- `grep -rniE 'hetzner|cc-setup|pramodbiligiri/cc-setup|<any host address>|ssh -i|id_ed25519' docker/ DOCKER.md`
  → no hits. (The only known private ref in the moved files is the Dockerfile
  `image.source` label; Task 3 changes it.)
- `docker/smoke.sh` (on a Docker-capable host, not CI): `./gradlew :docker:buildImage`
  builds a local image; the OCI labels are present and non-empty; `validateLabels
  -Plocal=true` exits 0. (Manual — documented, not automated here.)
- `settings.gradle.kts` module-graph comment mentions `docker` and its
  no-internal-deps nature.
- `DEVELOPMENT.md` has a "Docker image (Claude Code sandbox)" section; `docker/README.md`
  covers the build/publish flow and the three version channels, links to `DOCKER.md`
  for end-user usage, names no private repo, and carries the branch-build caveat.

## Out of scope

- The hosting notes (`server-notes*`, `connect-*.sh`, `login-options.md`,
  `statusline.sh`) — and any host address, hostname, SSH endpoint, or key name
  anywhere in this repo.
- Renaming / cleaning up the `cc-setup` repo itself (a follow-up, in that repo).
- Wiring an image build into `publishRelease`, or any GitHub Actions / CI for the
  image (this repo has no `.github/` workflows; `buildAndPush` stays a
  deliberately-run, human-invoked task).
- Multi-arch (`linux/arm64`) images — the image is `linux/amd64` only, unchanged.
- Any change to the runtime, plugin, or `DOCKER.md` end-user instructions.

---

### Task 1: `docker/` module — code, tests, Gradle wiring [Medium]

Bring the image-build tooling in as a compiling, testing Gradle module.

- Flat-copy into `docker/`: the 6 `io.bitken.ss.docker` main sources, the 4 test
  classes, `src/test/resources/npm-registry-claude-code.json`, `Dockerfile`,
  `test/smoke.sh` → `docker/smoke.sh`.
- `docker/build.gradle.kts`:
  - `plugins { id("shipsmooth.java-conventions") }`
  - `dependencies { implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2") }`
    (match siblings; convention plugin supplies JUnit).
  - Port the four `JavaExec` task registrations from `cc-setup/build.gradle.kts`
    into `group = "docker"`: `resolveVersions`, `buildImage`, `buildAndPush`,
    `validateLabels`. Keep the `-Pimage` / `-Plocal` / `-PdockerRepo` property
    handling. Keep `buildAndPush` **wired-only** (never a `dependsOn` of an
    aggregate) with the same "outward-facing, run deliberately" comment.
  - **`workingDir = layout.projectDirectory.asFile`** on every task that shells
    out to `docker` (at least `buildImage`, `buildAndPush`, `validateLabels`) so
    `BuildAndPushImage`'s `user.dir`-derived Dockerfile / context / overview
    paths resolve inside `docker/`.
  - Pass the shipsmooth version from `plugin.version` — see Task 2 (this task may
    leave a `TODO` and read a literal, or just do it now; Task 2 owns the doc +
    comment).
- `docker/.dockerignore`: `src/`, `build/`, `.gradle/`, `*.md`.
- `settings.gradle.kts`: `include("docker")` + extend the module-graph comment
  ("`docker` — image build tooling; no internal deps, consumes the published
  plugin").
- Adjust `docker/smoke.sh`'s `REPO_ROOT` (it did `cd "$(dirname …)/.."` from
  `test/`; now the script sits at the module root, so `REPO_ROOT="$(cd "$(dirname
  "${BASH_SOURCE[0]}")" && pwd)"`), and update the `./gradlew` invocations to
  `./gradlew :docker:buildImage` / `:docker:validateLabels` run from repo root
  (or keep them bare and `cd` to repo root — pick one, document it in the header).

**Test-first:** the 4 ported test classes are the failing-then-passing evidence —
commit them (plus resources) first and confirm `./gradlew :docker:test` **fails**
with "no such module / task" or compile errors, then add `build.gradle.kts` +
`settings.gradle.kts` wiring to green. Any genuinely new assertion (e.g. a test
that `resolveVersions` surfaces `plugin.version`) is written before its wiring.

De-risk focus: does `./gradlew :docker:test` pass unchanged under the convention
plugin (JUnit 5.10.2, `inferModulePath=false`, jacoco finalizer)? Does
`:docker:resolveVersions` still reach npm and print three lines? Prove those two
before hardening.

Medium: no logic change, but four unknowns converge — convention-plugin toolchain
vs. `cc-setup`'s standalone script, the `user.dir`/`workingDir` fix, `smoke.sh`
path rework, and the reactor now compiling/testing this module on every
`./gradlew build`.

### Task 2: source the shipsmooth version from root `plugin.version` [Low]

*Depends-on: 1*

- `docker/build.gradle.kts`: the value handed to `ResolveVersions` /
  `BuildAndPushImage` as the shipsmooth version becomes
  `providers.gradleProperty("plugin.version").get()` (mirror
  `packaging/build.gradle.kts`'s `findProperty("plugin.version")`). Remove any
  `shipsmoothVersion` property lookup and any fallback literal.
- Do **not** add `shipsmoothVersion` to the root `gradle.properties`.
- Code comment at the wiring site: the image installs the **latest published**
  plugin from the `bitkentech` marketplace; `plugin.version` and the published
  version are expected to agree at release time (the release bumps
  `plugin.version` and publishes in the same pass).
- Test: `./gradlew :docker:resolveVersions` prints `shipsmooth=<X>` where `<X>` ==
  the current `plugin.version` (`0.3.36` today); a `ResolveVersionsTest` case (or
  an assertion on `Versions.compoundTag()`) pins that the version threaded
  through is whatever the caller passes — no hidden default.

Low: one-line wiring change onto an established pattern; the Java already takes
the version as an argument.

### Task 3: docs — maintainer guide, source label, private-ref scrub [Low]

*Depends-on: 1*

- `docker/README.md` — maintainer build/publish reference, ported and trimmed
  from `cc-setup/DEVELOPMENT.md`:
  - the task table (`resolveVersions` / `buildImage` / `validateLabels` /
    `buildAndPush`), now as `./gradlew :docker:<task>`.
  - `buildAndPush` prerequisites (`CLAUDE_API_KEY`, `docker login`,
    `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`) and the "outward-facing, never a
    side effect" note.
  - the three version channels (OCI labels / Overview table / compound tag) and
    their drift risks.
  - the shipsmooth version now comes from root `plugin.version` (no manual pin);
    Claude Code is resolved live from the npm `stable` dist-tag.
  - **one sentence:** a branch-built image installs the last *published* plugin,
    not the working tree.
  - a link to repo-root `DOCKER.md` for end-user usage; **no** private-repo name
    or link, no "hosting / cloud box" content.
- `Dockerfile`: `org.opencontainers.image.source` →
  `"https://github.com/bitkentech/shipsmooth"`.
- `DEVELOPMENT.md`: add a short "Docker image (Claude Code sandbox)" subsection
  near the release material pointing at `docker/README.md`.
- Scrub: `grep -rniE 'hetzner|cc-setup|<host address patterns>|ssh -i|id_ed25519|
  rawlinux|codegen'` across `docker/` and `DOCKER.md` → zero hits (beyond the
  label already fixed).
- README-root: no change needed — plan-112's method 2 quickstart already stands.

Low: prose + one Dockerfile line; no code.

<!-- execution order (risk-sorted, deps respected): 1 (Med, hard dep) -> 2 (Low) -> 3 (Low) -->
