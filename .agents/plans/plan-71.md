# Plan 71 — Gradle Skills Trial (`skills/pkg`)

## Context

The proposal in `docs/proposals/build-migrate.md` recommends a **skills trial** as the
go/no-go gate for a full Maven→Gradle migration. The logic: `skills/pkg` is the
best-case module (highest upside, lowest risk). If Gradle doesn't feel clearly better
there, abandon the migration entirely — `cli`/`core`/`packaging` have less upside and
far more risk.

This plan is **Phase 0** of that sequence: port `skills/pkg` to Gradle on a throwaway
branch and develop against it for real. Verify `build/` output matches `mvn compile`
once (parity sanity check), then judge whether the loop actually feels better.

Backlog feature: tracked in plan narrative only (no separate Linear issue — Local mode).

## Objectives

1. Replace the brittle `dist -nt tasks/session-start.ts` mtime hack with real
   task input/output tracking (`node-gradle` plugin).
2. Collapse the four-plugin JTE chain (antrun → jte-maven-plugin → build-helper →
   maven-compiler) into `stageJte` Copy task + `gg.jte.gradle` wiring.
3. Produce variant tasks (`renderClaudeDev`, `renderGeminiDev`) as explicit `JavaExec`
   tasks instead of profile-activated `exec:java`.
4. Verify `build/` output is byte-equivalent (or behaviour-equivalent) to the Maven
   output before declaring success.

## Scope

- **In scope:** `skills/pkg/build.gradle.kts`, `buildSrc` convention plugin (Semeru 25
  toolchain), `settings.gradle.kts` for the `skills` sub-project only.
- **Out of scope:** `core`, `cli`, `packaging`, `claude`, `gemini`, `devtools` — these
  are not touched until/unless Phase 0 passes.
- The existing `pom.xml` files are **not deleted** until parity is confirmed.

## Key facts from the proposal

- JDK is **Semeru/OpenJ9 25** at `/opt/installers/jdk-semeru/jdk-25.0.2+10`.
- `skills/pkg` current pipeline: npm install → tsc/esbuild → antrun rename `.jte.md`→`.jte`
  → `jte-maven-plugin generate` → `build-helper add-source` → `maven-compiler`.
- The `Target` render step takes **12 system properties** and writes to `build.outputDir`.
- Tests run on classpath (`useModulePath=false` not needed here — `skills/pkg` has no
  JPMS `module-info`).
- Existing tests: `EnvTest`, `OsTest`, `PlatformTest`, `PluginModelTest`, `TargetTest`,
  `TargetIntegrationTest`.

## Open questions

- Does `gg.jte.gradle` version 3.1.15 resolve the Semeru 25 toolchain cleanly, or does
  it need a version bump?
- Does `node-gradle` 7.1.0 work with system Node (no download) on this box?

---

## Tasks

### Task 1: Fix `repoRoot` resolved from `Paths.get(".")` [Medium]

*Depends-on:*

`Shipsmooth.java:38` passes `Paths.get(".")` as `repoRoot` to `ServicesModule`. Plan-70
wired `GitTags` to use `repoRoot` correctly, but never fixed the source value — so under
the packaged launcher (which sets its own CWD), `git tag` still runs in the wrong
directory and fails.

Fix: resolve `repoRoot` via `git rev-parse --show-toplevel` at startup instead of
trusting `"."`. If the command fails (not in a git repo), fall back to `Paths.get(".")`
and print a warning.

Acceptance: `shipsmooth plan tag --plan 71 --kind version` succeeds when invoked from a
subdirectory of the repo (e.g. `skills/pkg/`).

### Task 2: Add `plan branch --plan` for Local mode [Medium]

*Depends-on: 1*

The `plan branch` CLI subcommand currently requires `--issue <id>` (a Linear issue ID).
In Local mode there is no Linear issue, so callers must either pass a fake ID or bypass
the command entirely. Fix: make `--issue` optional when `--plan` is provided; in that
case derive the branch name as `t/{N}-{slug}` from the plan number.

Expected CLI shape after fix:
```
shipsmooth plan branch --plan 71 --desc "gradle-skills-trial"
# prints: git checkout -b t/71-gradle-skills-trial
#         git push -u origin t/71-gradle-skills-trial
```

Also update the SKILL.md `[Local]` branch-creation step to use this form.

Acceptance: `shipsmooth plan branch --plan 71 --desc "foo"` prints the correct git
commands without requiring `--issue`.

### Task 3: `buildSrc` convention plugin + `settings.gradle.kts` [Low]

*Depends-on: 2*

Add `buildSrc/src/main/kotlin/shipsmooth.java-conventions.gradle.kts` with:
- Semeru 25 toolchain (`JvmVendorSpec.IBM`)
- UTF-8 compiler encoding
- `mavenLocal()` + `mavenCentral()` repositories (local repo at `/opt/mvn/repository`)
- JUnit Jupiter test dependency
- `modularity.inferModulePath.set(false)` on `tasks.test`

Add `settings.gradle.kts` scoped to the `skills` sub-project only (not root):
```kotlin
rootProject.name = "skills-pkg"
// no other modules included — Phase 0 is skills:pkg only
```

Acceptance: `./gradlew help` (inside `skills/pkg/`) resolves the toolchain and exits 0.

### Task 4: Node/TS pipeline with real input tracking [Medium]

*Depends-on: 3*

Replace the brittle `[ dist -nt tasks/session-start.ts ] || npm run build` mtime check
with a `node-gradle` `NpmTask` that declares:
- `inputs.dir("scripts/tasks")`
- `inputs.file("scripts/package.json")`
- `outputs.dir("scripts/dist")`

Also wire `npmInstall` with `inputs.file("scripts/package-lock.json")` /
`outputs.dir("scripts/node_modules")`.

Use `node { download.set(false) }` (system Node, matching current Maven behaviour).

Acceptance: edit a `.ts` file in `scripts/tasks/` → `./gradlew compileTs` re-runs tsc;
edit an unrelated file → `compileTs` is UP-TO-DATE.

### Task 5: JTE staging + precompile [Medium]

*Depends-on: 3*

Replace the antrun + jte-maven-plugin + build-helper chain with:
1. `stageJte` Copy task: copies `start/`, `experimental/`, `shared/` from repo root,
   renames `*.jte.md` → `*.jte`, outputs to `build/jte-src/`.
2. `gg.jte.gradle` plugin pointed at `stageJte.destinationDir`, `contentType = Plain`.
3. Generated sources wired into `compileJava` automatically by the JTE plugin.

Acceptance: `./gradlew compileJava` produces `.class` files for the JTE-generated
templates; `javap` on one confirms it compiled.

### Task 6: `Target` render variant tasks [Medium]

*Depends-on: 4, 5*

Add two `JavaExec` tasks — `renderClaudeDev` and `renderGeminiDev` — that invoke
`io.bitken.ss.resources.Target` with the same 12 system properties the current Maven
`render-plugin-resources` execution uses, reading values from Gradle properties
(`-P` flags or `gradle.properties`).

Define a `RenderSpec` value object (data class or simple Kotlin class in `buildSrc`)
holding the 12 variables so both tasks share one definition and can't drift.

Acceptance: `./gradlew renderClaudeDev` produces a `build/` directory; diff against
`mvn compile` output shows no meaningful differences (whitespace/timestamp noise
acceptable).

### Task 7: Existing tests pass under Gradle [Low]

*Depends-on: 5*

Wire `src/test/java` to the Gradle test task. Confirm all six existing test classes
(`EnvTest`, `OsTest`, `PlatformTest`, `PluginModelTest`, `TargetTest`,
`TargetIntegrationTest`) pass under `./gradlew test`.

Acceptance: `./gradlew test` green; coverage report generated.

### Task 8: Parity verification + go/no-go judgement [Low]

*Depends-on: 6, 7*

Run both pipelines on a clean tree and diff the outputs:
```bash
mvn compile                    # Maven output → build/
./gradlew renderClaudeDev      # Gradle output → build-gradle/ (use a different outputDir)
diff -r build/ build-gradle/
```

Document the diff (if any) in a `docs/proposals/build-migrate-trial-result.md` note.
Record the subjective judgement: is the Gradle loop clearly better?

Acceptance: diff is empty or only contains known-acceptable differences (timestamps,
auto-generated comments). Go/no-go decision recorded in the note.
