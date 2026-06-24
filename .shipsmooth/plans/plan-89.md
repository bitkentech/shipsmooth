# plan-89 — fix release opencode npm automation

## Context

Feature (user's words): *"Fix new release process. Currently fails due to
opencode automation not working."*

Backlog/feature link (local mode, Core Invariant #3): **Reliable, resumable release
pipeline** — the release must complete its core artifacts (GitHub release + Windows
sibling) independent of the separately-authed npm publish, and the opencode npm publish
must be a standalone, idempotent, human-run step. This plan delivers the npm-decoupling
slice of that feature.

### Verified diagnosis

The release orchestrator is `packaging/src/main/java/io/bitken/ss/dist/PublishRelease.java`,
run via the Gradle `publishRelease` task
(`./gradlew publishRelease -Pshipsmooth.release.version=<X.Y.Z>`).

`run()` step order (PublishRelease.java:69–96):

1. `bumpAndCommitVersion()` — bumps `gradle.properties`, commits on `main` (not pushed)
2. `buildAndPackage()` — jlink 4 platforms, prod guard, assemble claude/codex/**opencode**
   prod payloads, validate, build 4 platform zips
3. `git checkout releases` → `syncDistAndPublish()` — commits `dist/` + `dist-codex/`,
   tags `v<ver>`, **pushes `releases` + tag**, **creates GitHub Release** with 4 zips
4. **`publishOpencodeNpm()`** — `npm publish build-opencode/` (line 90, 297–304)
5. `buildWindowsPlugin()` + `publishWindowsRelease()` — force-push `../shipsmooth-windows`

**The failure (memory `reference_release_process`, hit cutting 0.3.27 on 2026-06-24):**
`publishOpencodeNpm()` shells `npm publish` and **throws hard** (`runCommand` raises
`IOException` on non-zero exit) when the env has no `@bitkentech` npm publish token
(`npm whoami` → 401, publish → 401/404). Sitting at step 4 — after the GitHub
release/tag/branch push but before the Windows release — a throw leaves the release
**half-done**: GitHub Release + `releases` branch + tag live, Windows sibling repo
never updated, `main` bump never pushed.

### Decision (locked with the human)

npm publish needs a **separate credential** (`@bitkentech` scope) that is not reliably
present in the release env, unlike `gh`. So:

1. **`publishOpencodeNpm` is extracted into its own class** (`PublishOpencode`) that owns
   the assemble-check + idempotent `npm publish`.
2. **A new, self-contained `publishReleaseOpenCode` Gradle task** assembles + validates +
   publishes the opencode prod payload, run **separately** by a human with npm auth. It
   does not depend on a prior `publishRelease` run.
3. **The main `publishRelease` skips opencode-npm by default** via a flag
   (`-PpublishOpencodeNpm`, default `false`). The gated call stays in `run()` so a
   fully-authed operator can still do everything in one shot with `-PpublishOpencodeNpm=true`.
4. **Idempotent publish:** an "already published at this version" npm error (403 /
   `EPUBLISHCONFLICT`) is a **non-fatal no-op** (logged + return success), so re-running
   the separate task after a partial release does not error out.

### Relevant existing pieces

- `assembleOpencodeProd` (harness/opencode/build.gradle.kts:146) → `build-opencode/`
  (Sync, takes `-Pbuild.outputDir`).
- `ValidateRelease.validateOpencode(Path)` (ValidateRelease.java:57) — payload-only check.
- Current publish command: `PublishRelease.npmPublishOpencodeCommand` →
  `["npm","publish","<repoRoot>/build-opencode"]` (line 186–188).
- Gradle release-task conventions: `withDistDefaults()` + `args(version)` +
  per-target Semeru system properties (packaging/build.gradle.kts:75–80, 145–153).

## Tasks

> Risk-sorted (High → Low). Coverage target: confirm with human at execution
> start (default 95%; release-orchestrator Java has historically been unit-tested
> at the command-builder / pure-logic seams, matching `PublishReleaseTest`).

### Task 1: Extract `PublishOpencode` class with idempotent publish [Medium]
Create `io.bitken.ss.dist.PublishOpencode` owning the opencode npm publish:
assemble-check (`build-opencode/package.json` present), the `npm publish <dir>` command
(moved from `PublishRelease.npmPublishOpencodeCommand`), and idempotent-publish handling
— classify an "already published / version exists" npm failure (403 /
`EPUBLISHCONFLICT` / "cannot publish over") as a logged no-op returning success, while
real failures (401 auth, etc.) still throw. Expose a pure command-builder and a
classifier method for unit testing (mirrors `PublishReleaseTest`'s seams — no live npm).
Highest spiral risk: this is the new logic and the idempotency classifier is the part
most likely to be wrong.

### Task 2: Self-contained `publishReleaseOpenCode` Gradle task [Medium]
*Depends-on: 1*
Register a new `release`-group `publishReleaseOpenCode` JavaExec task in
`packaging/build.gradle.kts` that: depends on `:harness:opencode:assembleOpencodeProd`
(into `build-opencode/`), validates the payload (`ValidateRelease.validateOpencode`),
then runs `PublishOpencode`. Self-contained — runnable without a prior `publishRelease`.
Wire `withDistDefaults()` + version arg like the other dist entrypoints.

### Task 3: Gate opencode-npm out of `publishRelease`, run last [Low]
*Depends-on: 1*
In `PublishRelease.run()`, **move** the opencode-npm step to the **very end** — after
`buildWindowsPlugin()` + `publishWindowsRelease()` — and have it delegate to
`PublishOpencode`, gated on a `publishOpencodeNpm` flag that defaults to **false**
(plumbed from `-PpublishOpencodeNpm` through the `publishRelease` task args / a system
property). Default release run no longer attempts npm at all; even when explicitly
enabled (`-PpublishOpencodeNpm=true`), being last means a late npm failure can no longer
strand the GitHub or Windows releases.

### Task 4: Update release docs + memory [Low]
*Depends-on: 2,3*
Update any in-repo release docs/scripts that reference the one-shot npm step to describe
the new two-command flow (`publishRelease` then `publishReleaseOpenCode`), and refresh
the `reference_release_process` memory's npm gotcha to point at the separate task.