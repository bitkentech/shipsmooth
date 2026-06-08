# Plan 74 — Dev jlink runtime wiring: lazy `jlinkDir`, host detection, drop `-PjlinkBuild`

## Context

`./gradlew :claude:devInstall` (a convenience wrapper added mid-session to assemble the
full claude-dev payload into repo-root `build/`) produces a plugin whose
`session-start-config.json` points at a **non-existent runtime image**. Investigation
found three compounding defects:

1. **Stale path (plan-71 migration miss).** The dev `jlinkDir` in
   `skills/pkg/build.gradle.kts` is hardcoded to the Maven path
   `cli/target/jlink-image`. Maven was removed in plan-73; the Gradle cli build emits
   per-platform images to `cli/build/jlink-image-<platform>`. The dev `jlinkDir` was
   therefore **never correct** post-migration — it just wasn't exercised until a dev
   install was attempted.

2. **No dependency edge.** `jlinkDir` is a config-time frozen `String` baked into the
   `renderClaudeDev` task's inputs. `installRuntime` (`session-start.ts`) copies that dir
   **verbatim** into the runtime cache (no platform-suffix logic). Because there is no
   modeled producer→consumer dependency from the render to the jlink image, running
   `:claude:devInstall` does **not** build the image — so even with the right path the
   dir is empty and the installer silently falls through to the release-download path.

3. **Hardcoded platform + the `-PjlinkBuild` smell.** The host platform was pinned in the
   string. Worse, the jlink tasks themselves only **exist** when `-PjlinkBuild` is set
   (`if (project.hasProperty("jlinkBuild")) { tasks.register(...) }` in BOTH
   `cli/build.gradle.kts` and `core/build.gradle.kts`). Conditional task *registration*
   is a Maven-profile holdover and an anti-pattern: it makes the tasks unresolvable for
   lazy `Provider` wiring and defeats Gradle's lazy-configuration model.

### Root cause

`jlinkDir` should be a **lazy `Provider<String>` resolved from the producing jlink task's
output**, not a config-time string. Modeling it as a real task output establishes the
dependency edge that makes `devInstall` build the image automatically, makes host
detection happen at read time, and removes the need for the `-PjlinkBuild` existence gate.

### Backlog feature

Parent feature (permanent): **"Maven→Gradle build migration & dev-loop tooling"** — the
line of work delivered by plans 71/72/73. This plan completes the dev-install loop and
removes a migration-era smell. (No external tracker; recorded here per Local mode,
Core Invariant #3.)

### Constraints / invariants discovered

- **core + cli un-gate together.** cli's `jlinkImage_*` reads core's shaded
  `reinjectModuleInfo` jar. Both `-PjlinkBuild` guards must drop together or cli jlink
  links an unshaded core.
- **Shadow plugin is already applied unconditionally** in core (only its *config* is
  guarded) — un-gating the config block is safe.
- **`PublishRelease.java` drives the release jlink** via `jlinkBuildCommand()`, which
  passes `-PjlinkBuild` + lists all four `:cli:jlinkImage_*` tasks. Removing the flag
  must keep this working (release builds all four platforms explicitly).
- **`RenderSpec` is the drift-guard** (buildSrc, shared by all 5 variants). Making
  `jlinkDir` lazy must keep one consistent shape across dev/prod/gemini/windows
  (user: consistency over per-variant perf).
- **Normal builds must NOT trigger jlink.** Un-gating registration must not cause
  `./gradlew build` to resolve jars / run jlink at config time → use lazy
  `argumentProviders {}` for the jlink command line.
- **Windows is a cross-build target**, not host-derived: its `jlinkDir` stays pinned to
  the `windows-x64` image regardless of build host.

---

## Tasks

### Task 1: Un-gate core jlink shading (remove `-PjlinkBuild` guard in core) [High]

*Depends-on:*

Remove the `if (project.hasProperty("jlinkBuild"))` wrapper in `core/build.gradle.kts`;
register `shadowJar` config + `reinjectModuleInfo` unconditionally (lazy — zero cost
unless pulled into the graph). Ensure no eager jar/classpath resolution at config time:
the `semeruHome` property read stays defaulted; the Exec command line resolves lazily.
Verify `./gradlew :core:build` does NOT run shadowJar/reinjectModuleInfo, and
`./gradlew :core:reinjectModuleInfo` still produces the shaded module-info-bearing jar.

**Risk: High** — touches the Shadow/module-info chain that the entire jlink runtime
depends on; an eager-resolution regression would slow/break every build.

### Task 2: Un-gate cli jlink tasks + lazy command line [High]

*Depends-on: 1*

Remove the `-PjlinkBuild` guard in `cli/build.gradle.kts`; register `jlinkImage_*`,
`writeSccLauncher`, `jlinkSmoke*` unconditionally. Move `runtimeModulePath()` resolution
out of the eager `commandLine(...)` into a lazy `argumentProviders {}` block (and
similarly for `writeSccLauncher`'s module path) so registering the tasks resolves no
jars/classpath at config time. Keep semeru/jre/jmods as defaulted gradle properties.
Verify `./gradlew build` triggers NO jlink/jar resolution at config time, and
`./gradlew :cli:jlinkImage_linux-x64` still builds the image.

**Risk: High** — same eager-resolution hazard; this is the task most able to regress
config-time performance for all builds.

### Task 3: `HostPlatform.tag()` buildSrc helper [Low]

*Depends-on:*

Add `buildSrc/src/main/kotlin/HostPlatform.kt`: `HostPlatform.tag()` mapping
`os.name`/`os.arch` → `linux-x64` / `darwin-x64` / `darwin-arm64` / `win32-x64`, matching
`detectPlatform()` in `skills/pkg/scripts/tasks/session-start.ts` (note JVM spellings:
`amd64`/`x86_64`→`x64`, `aarch64`/`arm64`→`arm64`). Unit-test the mapping for the four
supported tags + an unsupported-OS/arch error path.

**Risk: Low** — pure self-contained function with a unit test; no build-graph impact.

### Task 4: Lazy `RenderSpec.jlinkDir` across all variants [Medium]

*Depends-on: 3*

In `buildSrc/src/main/kotlin/RenderSpec.kt`: change `jlinkDir: String` →
`jlinkDir: Provider<String>` and `systemProperties()` →
`Map<String, Provider<String>>` (wrap all keys uniformly, one shape). Update the
buildSrc unit/contract expectations if any. This is a shared type — it will not compile
until Task 5 updates all construction sites, so Tasks 4 + 5 land together but are split
for review clarity (4 = type change, 5 = wiring).

**Risk: Medium** — shared type used by all 5 render variants; a missed provider
conversion breaks the build for every target.

### Task 5: Wire dev `jlinkDir` to the cli jlink task output; convert all specs [Medium]

*Depends-on: 2, 4*

In `skills/pkg/build.gradle.kts`:
- Dev spec `jlinkDir` = a `Provider<String>` from the cli `jlinkImage_${HostPlatform.tag()}`
  task's output dir (`cliProject.tasks.named(...).map { it.outputs.files.singleFile.path }`),
  establishing the producer→consumer edge.
- Prod (`/dev/null`), gemini-prod (`""`), windows (`cli/build/jlink-image-windows-x64`):
  wrap their literals in providers for the same shape.
- Update the render task's `systemProperties(...)` + `inputs.property(...)` loop to accept
  `Provider` values.
- Drop the stale `val jlinkDir = repoRoot.dir("cli/target/jlink-image")...` line.

Verify `./gradlew :skills:pkg:renderClaudeDev` resolves and that requesting it pulls in
`:cli:jlinkImage_<host>` (dependency edge present in `--dry-run`).

**Risk: Medium** — cross-project lazy wiring (skills:pkg → cli); the dependency edge is
the crux that makes the whole fix work, and cross-project `tasks.named` ordering can be
fragile.

### Task 6: Simplify `:claude:devInstall`; drop release `-PjlinkBuild` [Low]

*Depends-on: 5*

- `claude/build.gradle.kts`: keep `devInstall` assembling claude-dev into repo-root
  `build/`; the jlink image now arrives via the render dependency (no manual `dependsOn`,
  no flag).
- `packaging/.../PublishRelease.java`: remove `-PjlinkBuild` from `jlinkBuildCommand()`
  (the four `:cli:jlinkImage_*` task names stay). Confirm the release path is otherwise
  unchanged.

**Risk: Low** — mechanical removal once the gates are gone; covered by the integration
test.

### Task 7: Docs + final verification [Low]

*Depends-on: 6*

- `DEVELOPMENT.md`: drop `-PjlinkBuild` from the documented `jlinkImage_windows-x64`
  invocation and the release notes; add a one-line `:claude:devInstall` dev-loop entry.
- `docs/proposals/build-migrate.md`: leave as historical (it's a proposal record), but
  note if any active instruction references the flag.
- Full verification:
  - clean `./gradlew :claude:devInstall` → host jlink image builds, `build/` payload
    complete (`.claude-plugin/`, `dist/`, `hooks/`, `skills/`),
    `session-start-config.json.jlinkDir` = `cli/build/jlink-image-<host>`.
  - `./gradlew build` → green, and NO jlink/shadow tasks executed (check task list).
  - `./gradlew :core:reinjectModuleInfo :cli:jlinkImage_linux-x64` → both succeed.

**Risk: Low** — docs + verification; no production code.

---

## Integration tests (Phase 2 preamble)

1. **Gradle dependency-edge test:** assert that requesting `:skills:pkg:renderClaudeDev`
   (or `:claude:assembleClaudeDev`) includes `:cli:jlinkImage_<host>` in the task graph
   (`./gradlew :claude:assembleClaudeDev --dry-run` contains the jlink task), AND that
   `./gradlew build --dry-run` does NOT. This is the behavioral contract for the whole
   plan (auto-build-on-devInstall + no-jlink-on-normal-build).
2. **Rendered-config test:** after `:claude:devInstall`, `session-start-config.json`'s
   `jlinkDir` equals the host `cli/build/jlink-image-<HostPlatform.tag()>` path and that
   directory exists and contains `bin/shipsmooth`.

(Keep to ≤2 integration tests per the workflow; both must fail before any task code.)
