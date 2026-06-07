# Plan 71 — Maven→Gradle Migration (skills trial + full reactor)

## Context

The proposal in `docs/proposals/build-migrate.md` recommends a **skills trial** as the
go/no-go gate for a full Maven→Gradle migration. The logic: `skills/pkg` is the
best-case module (highest upside, lowest risk). If Gradle doesn't feel clearly better
there, abandon the migration entirely — `cli`/`core`/`packaging` have less upside and
far more risk.

**v1 (Tasks 1–8) was Phase 0**: port `skills/pkg` to Gradle on a throwaway branch and
develop against it for real. Outcome (see `docs/observations/build-migrate-trial-result.md`):
byte-for-byte parity on both render variants, real dev-loop incrementality, all tests
green. Trial passed.

**v2 (Tasks 9+) expands scope to the full migration.** Following an explicit Go
decision, this plan now also covers Phases 1–5 from the proposal: structure, codegen
parity, jlink + dagger-shade, assembly/release, and cutover. The `t/71-gradle-skills-trial`
branch is no longer throwaway — it is the migration branch. This is a scope pivot, hence
the version bump to `plan-71-v2`.

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

**v1 (Phase 0, Tasks 1–8) — done:** `skills/pkg` only.

**v2 (Phases 1–5, Tasks 9+) — full reactor:**
- **In scope:** root `settings.gradle.kts` (all 7 modules), full `buildSrc` conventions,
  and a `build.gradle.kts` for each of `core`, `cli`, `packaging`, `claude`, `gemini`,
  `devtools` — reproducing JAXB `xjc`, Dagger APT + shade, the templated `Build`
  constants, the 5-platform jlink images, the OpenJ9 SCC launcher, and the
  `ValidateRelease`/`PackageRuntime`/`PublishRelease` entrypoints.
- **Parity is the acceptance test** (Core Invariant in the proposal): every phase must
  produce byte- or behaviour-equivalent output vs Maven, diffed before sign-off.
- The existing `pom.xml` files are **not deleted** until full parity is confirmed across
  all four payloads — deletion is the final cutover task only.
- **Semeru vendor pin returns for `core`/`cli`:** unlike `skills/pkg`, these need OpenJ9
  (SCC, `zip-9` jlink), so the toolchain must resolve Semeru 25 specifically.

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

---

## v2 — Full migration (Phases 1–5)

Tasks below were added at `plan-71-v2` after the Phase 0 trial passed. Each phase is
parity-gated: diff Gradle output against Maven before marking the task done. The
`pom.xml` files stay until Task 17.

**Ordering note:** these are NOT pure risk-sorted because the phases form a hard
dependency chain (you cannot shade `core` before `core` compiles, etc.). Per the
workflow's dependency-over-risk exception, the phase sequence is preserved.

**v3 change:** former Task 12 (core codegen) merged into Task 10 — `core` can't compile
without its generated types, so the compile/codegen split was artificial. Task 10 is now
High (absorbs the codegen risk); Task 12's number is retired. Risk levels: 9 Medium,
**10 High**, 11 Medium, 12 (retired), 13/14 High, 15 Low, 16/17 High.

**v16 change:** added Task 26 (Gradle-native release path), and Task 17 now depends on it.
The cutover was unshippable as written: deleting the poms strands the project with no way to
release. Three release mechanisms still shell out to `mvn` — `PublishRelease.java` (the
canonical entrypoint, ported to a `JavaExec` task in Task 16 but whose *Java code* still runs
`mvn versions:set`, `mvn -Pjlink package`, and `mvn compile -Pprod/-Pwindows`), the
`release.sh`/`release-gemini.sh` shell scripts, and `package-tasks-java.sh`. Task 17's
acceptance ("no remaining `mvn` invocation in docs/scripts") silently required this work but
gave it no home. Task 26 makes the release path Gradle-native **before** the poms are deleted,
so the migration can be dry-run-verified against Maven while both still exist. Also corrected
Task 17's pom count: there are **nine** real `pom.xml` files (root reactor + `skills/`
aggregator + 7 modules), not seven — all nine must go for `./gradlew build` to work pom-free.

### Phase 1 — Structure

### Task 9: Root `settings.gradle.kts` + full `buildSrc` for the reactor [Medium]

*Depends-on: 8*

Promote the `skills/pkg`-scoped `settings.gradle.kts` to a **root** one including all
seven modules (`core`, `cli`, `skills:pkg`, `claude`, `gemini`, `packaging`, `devtools`).
Move `buildSrc` to the repo root. Reinstate the **Semeru vendor pin** in the convention
plugin (required by `core`/`cli` for SCC + `zip-9` jlink) — but keep a `skills/pkg`
override or vendor-agnostic path so the skills build still works on any JDK 25.

This is structural only: no module compiles yet beyond skills.

Acceptance: `./gradlew projects` lists all seven modules and resolves the Semeru
toolchain; `./gradlew :skills:pkg:test` still green after the relocation.

### Task 10: `core` compiles WITH codegen (JAXB xjc + Dagger APT + Build constants) [High]

*Depends-on: 9*

**(v3: merged with the former Task 12.)** The hand-written `core` sources reference
generated types deeply — `io.bitken.ss.jaxb.*` across 5+ files, Dagger `@Component`
output, and the `Build` constants — so there is no meaningful "compiles without codegen"
intermediate state. Wire all three codegen mechanisms and compile in one task:

- **JAXB `xjc`** from `core/src/main/resources/plan-tasks.xsd` → `io.bitken.ss.jaxb`
  (via the `com.github.bjornvester.xjc` plugin or an `xjc` JavaExec).
- **Dagger APT** (`annotationProcessor` dep) generating `DaggerAppComponents` — runs as
  part of `compileJava`, mirroring Maven's `annotationProcessorPaths`.
- **Build constants** (`VERSION`, `EXPERIMENTAL_BUILD`) — a `Copy`+`expand` task replacing
  `templating-maven-plugin`, wired into the main source set.

Stand up `core/build.gradle.kts` with picocli/jackson/jakarta deps + the
`shipsmooth.java-conventions` plugin. Tests on the classpath (`useModulePath=false`).
**No shade/jlink yet** — that stays in Task 13.

Acceptance: `diff` Gradle-generated sources against Maven `target/generated-sources`
(same JAXB packages, same `Build` constants); `./gradlew :core:test` green.

### Task 11: `cli` compiles against `core` (no jlink) [Medium]

*Depends-on: 10*

`cli/build.gradle.kts` with `implementation(project(":core"))` + picocli; apply the
`application` plugin. No jlink yet — just a working `compileJava` + `test`.

Acceptance: `./gradlew :cli:test` green; `./gradlew :cli:run --args="--help"` prints
usage.

### Phase 2 — Codegen parity

### Task 12: (merged into Task 10 at v3)

The "core codegen" work was folded into Task 10 — `core` cannot compile without its
generated types, so splitting compile-then-codegen was an artificial boundary. See
Task 10. This number is intentionally retired (not renumbered, to keep downstream
dependency references stable).

### Phase 3 — jlink + shade (highest risk)

### Task 13: Conditional Dagger shade + `module-info` re-injection [High]

*Depends-on: 10*

Under a `-PjlinkBuild` flag, shade `com.google.dagger:dagger` + `jakarta.inject` into the
`core` jar (Shadow plugin), strip `META-INF/*.SF|DSA|RSA`, then **re-inject
`module-info.class`** with the Semeru `jar --update` (Shadow strips it; `core` must stay a
named module). This is the single highest-risk item in the migration.

Acceptance: the shaded `core` jar is a named JPMS module (`jar --describe-module` shows
`io.bitken.ss.core`) and contains `dagger.internal.*`; tests still pass on the classpath.

### Task 14: 5-platform jlink images + SCC launcher + smoke tests [High]

*Depends-on: 13*

Port the `cli` jlink profile: the hand-pinned ~16-jar runtime module-path (verbatim from
`cli/pom.xml`, including the *shaded* core jar in place of the plain one), `--add-modules
io.bitken.ss.cli,openj9.sharedclasses`, `--compress zip-9`, `--launcher`, for all five
platforms (linux-x64, darwin-x64, darwin-arm64, windows-x64 + the JRE variant). Emit the
OpenJ9 SCC launcher (`-Xquickstart -Xshareclasses`). Reproduce the `verify`-phase smoke
tests that run **through** the launcher from the repo root.

Acceptance: `./gradlew :cli:jlinkImage_linux-x64` produces an image whose contents match
the Maven image (module set + launcher); the SCC smoke tests (`--help`, `plan show
--plan 27`) pass through the launcher.

### Phase 4 — Assembly + release

### Task 15: `claude`/`gemini` manifest filtering [Low]

*Depends-on: 11*

Replace the `maven-resources-plugin` filtering in `claude` and `gemini` with `Copy` +
`expand()` tasks (mind JSON brace escaping). `claude` filters `.claude-plugin/*` +
`marketplace.json`; `gemini` filters `gemini-extension.json` + copies `commands/`
(distinct source for `gemini` vs `gemini-dev`).

Acceptance: filtered manifests are byte-identical to the Maven `target/` output for both
the prod and dev variants.

### Task 16: `packaging` assembly + release entrypoints [High]

*Depends-on: 14, 15*

Port `packaging`: `copy-dist` (compiled JS minus `*.test.js`), then `JavaExec` tasks for
`ValidateRelease`, `PackageRuntime` (per platform: linux-x64, darwin-x64, darwin-arm64,
win32-x64), and `PublishRelease`. The dev "verify jlink image exists" guard becomes a task
precondition. These stay `JavaExec`-with-args (no type-safety gain — parity only).

Acceptance: a dry-run `PackageRuntime` for linux-x64 produces a `runtime-<ver>/` payload
byte/behaviour-equivalent to the Maven output; `ValidateRelease` passes on it.

### Task 18: Prod render variant tasks [Medium]

*Depends-on: 5, 6*

Added at `plan-71-v3`: Task 6 (the Phase-0 trial) scoped only the *dev* render variants
(`renderClaudeDev`, `renderGeminiDev`); no task ever created the prod ones, so three of the
four prod payloads can't be produced by Gradle. Task 17's four-payload diff depends on them.
Numbered 18 (next integer) but ordered before 17 via Task 17's `*Depends-on:*`.

Add three `JavaExec` render tasks in `skills/pkg/build.gradle.kts` — `renderClaudeProd`,
`renderGeminiProd`, and `renderWindows` — mirroring the existing dev render tasks but with
the prod tuples from the `prod`, `gemini`, and `windows` Maven profiles. Each is a new
`RenderSpec` passed to the existing `registerRender(...)` helper (the `RenderSpec` data class
and its `systemProperties()` mapping already exist in `buildSrc` and are parity-complete — no
infra changes). Prod deltas: `buildEnv=prod`, `experimentalEnabled=false`, prod
`pluginDescription`, `pluginSkillStartBasename=start`, empty/prod `skillFrontmatter`.
`renderWindows` additionally sets `buildOs=windows` and
`jlinkDir=cli/target/jlink-image-windows-x64`. The windows profile also sets
`skip.copy-dist=true`; that is a packaging *assembly* concern handled in Task 17, not here.

Acceptance: `./gradlew renderClaudeProd` (and the gemini/windows tasks) each produce their
prod payload; diff against the matching `mvn -P <profile> compile` output shows only
known-acceptable noise (timestamps, auto-generated comments).

### Task 20: Fix Windows SKILL cliBin path (forward `build.os` to the render) [Medium]

*Depends-on: 18*

Added at `plan-71-v6`. Latent bug found during Task 18: the Maven `render-plugin-resources`
exec (`skills/pkg/pom.xml`) has **never** forwarded `build.os` — `git log -S "<key>build.os</key>"`
is empty across all history. So `Target` always reads `build.os=posix`, and the shipped
`build-windows/skills/start/SKILL.md` carries the posix cliBin
(`${XDG_CACHE_HOME:-~/.cache}/.../bin/shipsmooth`) instead of the intended Windows path
(`%LOCALAPPDATA%\…\runtime\bin\shipsmooth.cmd`). The render *code* (`Os.Windows.cliBinPath`)
and its unit test (`TargetIntegrationTest` sets `build.os=windows` in-process) are correct —
the property just never reaches the real build. This is a half-wired feature from birth (the
`%LOCALAPPDATA%` cliBin landed in plan-61 `697e615`; the `windows` profile in pb-55 `ff0fb6c`),
not a regression — the May-25 v0.3.11 Windows install used the single posix SKILL; the Windows
machinery was all added afterward. Note the install itself works (driven by `install-runtime.bat`
via `hookCommand`, independent of the SKILL cliBin) — only the documented command path is wrong.

Fix: forward `build.os` to the render in **both** build systems. Maven: add
`<key>build.os</key><value>${build.os}</value>` to the `render-plugin-resources` exec. Gradle:
`RenderSpec.systemProperties()` already emits `build.os`, so set `windowsSpec.buildOs = "windows"`
(reverting the Task-18 parity workaround). After the fix, both systems render the
`%LOCALAPPDATA%` cliBin and stay byte-identical to each other. Regenerate the committed
`build-windows/` reference if one is tracked.

Acceptance: `build-windows/skills/start/SKILL.md` cliBin uses `%LOCALAPPDATA%\…\shipsmooth.cmd`
under both `mvn -P windows compile` and `./gradlew renderWindows`; the two outputs are
byte-identical; `TargetIntegrationTest` still green.

### Payload assembly — one task per variant

Reworked at `plan-71-v8`. Gradle has no way to assemble a **complete** payload into one tree
the way `mvn compile -P<profile>` does: the `claude`/`gemini` module tasks and the packaging
JS/TS copies already honour `-Pbuild.outputDir`, but the `skills/pkg` render tasks **hardcode**
their `outputDir` to `build/render/<variant>/`, so `skills/` + `hooks/` never join the
manifests + `dist/` + `scripts/`. The previously-committed `build*/` reference dirs were stale,
mutually inconsistent local artifacts (now deleted), so the parity baseline for each variant is
a **fresh** `mvn compile -P<profile>` build into a temp dir while the poms still exist.

Shared mechanism (landed in the first task, Task 21). **Assembly
does not invoke packaging** — packaging runs last, consuming the assembled payload + jlink image
to produce the runtime zip. The JS/TS copies (`copyDist`/`copyScripts`/`copyTsSource`) are
payload-assembly, not packaging, so they move out of the `packaging` module into
`skills:pkg`; packaging keeps only `PackageRuntime`/`ValidateRelease`/`PublishRelease`. The
post-build version stamp (release.sh `jq`) is out of scope — a release-script step, equal for
both systems.

**Dependency direction: integration → `skills:pkg`, never the reverse** (decided at
`plan-71-v10`). The `assembleClaude*` / `assembleGemini*` tasks live in the **`claude`** and
**`gemini`** integration modules, not in `skills:pkg`. `skills:pkg` is the low-level module: it
renders skills and owns the shared JS/TS, and knows nothing about claude or gemini. The shared
TS/JS (`session-start`) and `hooks` rendering stay in `skills:pkg` — they are consumed by **both**
claude and gemini (a fresh `gemini-dev` build ships the same `dist/session-start.js` + rendered
`hooks/`), so they are not claude-specific and do not move. `claude`/`gemini` are resource-only
modules (no Java classpath), so the render `JavaExec` stays *executed* in `skills:pkg`; the
integration module owns the *assembly* and its own manifests. (The earlier Task-21 draft put
`assembleClaudeDev` in `skills:pkg` reaching out to `:claude:copyPluginMeta` — that inverted
dependency is corrected here.)

**Isolation by audience** (decided at `plan-71-v15`). The point of per-integration assembly is
that someone working on Claude need not read, edit, or understand how Gemini is built, and vice
versa. So `assembleClaude*` lives in `claude/build.gradle.kts` and `assembleGemini*` in
`gemini/build.gradle.kts` — each fully self-contained, naming only its own producers
(`renderClaudeDev`, `copyDist`, `copyClaudeMetaDev`, …) and never referencing the other
integration. A **top-level root `build.gradle.kts` was rejected** for exactly this reason: it
would house all five variants together, forcing the Claude and Gemini owners into one shared file.
The reusable machinery — `VerifyNoOverlappingOutputs` and a small
`registerPayloadAssembly(name, payloadDir, producers…)` helper that wires the co-deposit +
overlap-check uniformly — lives in **`buildSrc`** as common helpers every integration calls. An
integration depending on `skills:pkg` producers is a normal downstream dependency (it consumes the
shared SDK), not an inversion.

**Dual-mode assembly** (decided at `plan-71-v11`, superseding v10's consumable-configuration
hand-off). Optimise the path run constantly (dev) for speed and the path run rarely
(prod/release) for correctness. Both modes reuse the **same** producer task definitions; only the
output dir passed and the assembly wiring differ.

- **Producers** (render, `copyDist`/`copyScripts`/`copyTsSource`, the per-variant manifest tasks)
  take the output dir as an **explicit parameter** (no shared global `-Pbuild.outputDir`) and
  declare **file/subpath-granular** outputs — NOT the whole payload dir. The render currently
  declares `outputs.dir(spec.outputDir)` = the whole dir, which defeats the check; each producer
  must instead declare its **exact output files** (see Bazel note below).
- **Producers declare exact output files (Bazel-style).** Decided at `plan-71-v13` after two
  rejected check designs (a declared-*directory* check can't tell `dist/` JS from
  `session-start-config.json`; a naive disk-walk can't attribute files when producers co-deposit
  into one dir — the walk sees the union for every producer). Bazel's model resolves this: every
  action declares its **exact** output files, and "one file → one producer" is an invariant of the
  build graph. Translated to Gradle: each producer declares `outputs.file(<each file>)` (render:
  its skills + hooks + `dist/session-start-config.json`; copyDist: its `.js` files; manifests:
  `plugin.json` + `marketplace.json`) instead of `outputs.dir(...)`. The render's produced set is
  deterministic, so it is accurately declarable. (Gradle, unlike Bazel, has no sandbox enforcing
  declared==actual, so the assemble task additionally walks the final payload dir and asserts every
  file present was declared by exactly one producer — catching both overlaps and stray undeclared
  files, the closest Gradle gets to Bazel's guarantee.)
- **Scope: payload content files only** (decided at `plan-71-v14`). The overlap-check polices
  ONLY the plugin **payload** output files — the `.md` / `.js` / `.json` / `.toml` / `.bat` /
  `.ts`(+`.d.ts`) that land in `build/<variant>/` — and ONLY for the payload-producing modules
  (`skills`, `claude`, `gemini`). It is **opt-in per `assembleX` task** over an **explicit list of
  registered payload producers**; it never auto-discovers tasks and never sees general build
  outputs (`.class`, JARs, generated sources, JTE template classes, `node_modules`, etc.). A
  file-extension allowlist (`{md, js, json, toml, bat, ts}` — the only types a fresh build of any
  of the five variants emits) is a second guard so a mis-registered non-payload output can't drag
  class files in. `core` / `cli` / `packaging` never get the check.
- **Dev** (`assembleClaudeDev`, `assembleGeminiDev`): producers co-deposit **directly** into
  `build/<variant>` — fast, no copy. Guarded by a `buildSrc` **overlap-check** that **fails the
  build** (Gradle's native overlapping-outputs detection is only a warning that silently disables
  incrementality, so the invariant would rot by accident — the check enforces it). The check reads
  each producer's **declared** `outputs.files`, keeps only the **file** entries (a `Copy`'s
  `into()` also auto-registers the dir; dir entries are skipped), and asserts them pairwise-disjoint
  — in-memory, no disk walk, and `UP-TO-DATE`-friendly. A separate completeness pass walks the
  assembled dir to catch undeclared strays (above).
- **Prod** (`assembleClaudeProd`, `assembleGeminiProd`, `assembleWindows`): producers write to
  their own private dirs; the assemble task `Sync`s them into `build/<variant>` as the **sole
  writer** — structurally overlap-immune, release-correct. The extra Sync is acceptable on the
  rare release path; the overlap-check is not needed in the prod path.

The `buildSrc` overlap-check is a shared prerequisite landed in Task 21 (alongside `assembleClaudeDev`).

**No default variants** (decided at `plan-71-v9`). The Maven `-Pvariant` / `activeByDefault`
fallback (`?: "dev"`) was a Maven-land UI shortcut and will **not** exist in Gradle. The
`claude` and `gemini` modules currently compute `tokens` / `outputDir` once at config time from
a global `-Pvariant` (defaulting to dev) and register a single manifest task — which makes it
impossible for `assembleClaudeDev` and `assembleClaudeProd` to both be correct in one build.
Instead, the manifest tasks become **per-variant** via a factory (like the render's
`registerRender(spec)`): e.g. `copyClaudeMetaDev` / `copyClaudeMetaProd`,
`copyGeminiDev` / `copyGeminiProd`. Each `assembleX` depends on its own variant's manifest task;
nothing reads a global `variant` property and the `?: "dev"` defaults are deleted. This refactor
lands incrementally — each variant task (21–25) adds the per-variant manifest task(s) it needs,
and the defaults are fully gone once all five exist.

Each task below migrates exactly one variant and is parity-gated against a fresh Maven build of
that variant before being marked done.

### Task 21: `assembleClaudeDev` [Medium]

*Depends-on: 18, 20*

First variant + the shared dual-mode mechanism. Lands: (1) the `buildSrc` **overlap-check** task
(fails on intersecting declared producer outputs, file-granular, declares its inputs so it stays
`UP-TO-DATE`); (2) tightened **file/subpath-granular** output declarations on the render + copy
producers (replacing the current whole-dir `outputs.dir`); (3) producers taking an **explicit
output dir** parameter; (4) `assembleClaudeDev` in the **`claude`** module — **dev co-deposit**:
producers write directly into `build/claude-dev`, gated by the overlap-check, plus the per-variant
`copyClaudeMetaDev` manifest task (no global `-Pvariant` default). See the dependency-direction,
no-default-variants, and dual-mode notes above. (Prod/Sync mode arrives with the prod variant
tasks 23/24/25.)

Acceptance: `./gradlew assembleClaudeDev` produces `build/claude-dev` byte-identical to a fresh
`mvn compile -Pdev` build (modulo the jq version stamp); the overlap-check passes and is
`UP-TO-DATE` on a no-op rebuild; deliberately pointing two producers at an overlapping path fails
the check; existing tests green.

### Task 22: `assembleGeminiDev` [Medium]

*Depends-on: 21*

Assemble the gemini **dev** payload (render gemini-dev + `gemini-extension.json` + `commands/` +
README + any applicable JS/TS copies) into one dir.

Acceptance: `./gradlew assembleGeminiDev` byte-identical to a fresh `mvn compile -P 'gemini-dev'`
build (modulo jq stamp).

### Task 23: `assembleClaudeProd` [Medium]

*Depends-on: 21*

Assemble the claude **prod** payload (render claude-prod + prod manifests + JS/TS copies).

Acceptance: `./gradlew assembleClaudeProd` byte-identical to a fresh `mvn compile -Pprod -P'!dev'`
build (modulo jq stamp).

### Task 24: `assembleGeminiProd` [Medium]

*Depends-on: 22, 23*

Assemble the gemini **prod** payload.

Acceptance: `./gradlew assembleGeminiProd` byte-identical to a fresh
`mvn compile -P 'gemini,!dev,!claude'` build (modulo jq stamp).

### Task 25: `assembleWindows` [Medium]

*Depends-on: 23*

Assemble the **windows** payload (render windows + claude manifests; **no** `copyDist` —
windows skips it). Includes the windows `hooks/` (cmd.exe + `install-runtime.bat`) from Task 20.

Acceptance: `./gradlew assembleWindows` byte-identical to a fresh `mvn compile -P windows` build
(modulo jq stamp).

### Phase 5 — Cutover

### Task 26: Gradle-native release path (de-Maven the release entrypoints) [High]

*Depends-on: 14, 16, 23, 24, 25*

Added at `plan-71-v16`. The cutover (Task 17) deletes the poms, but the release path is still
100% Maven, so without this task the migration ships a repo that cannot release. Three
mechanisms shell out to `mvn`:

1. **`PublishRelease.java`** (`packaging/src/main/java/io/bitken/ss/dist/PublishRelease.java`)
   — the canonical entrypoint per `DEVELOPMENT.md`, ported to a `JavaExec` task in Task 16 but
   whose *Java code* still runs Maven in four places: `mvn versions:set` (L94),
   `mvn -pl cli -am -Pjlink … package` (L130), `mvn compile -Pprod -P!dev` (L135), and
   `mvn compile -Pwindows -P!dev` (L154). Wrapping it in a Gradle task did not de-Maven it.
2. **`release.sh` / `release-gemini.sh`** — `mvn versions:set` + `mvn compile -P<profile>`.
3. **`package-tasks-java.sh`** — `mvn -pl app help:evaluate` (version) + assumes
   `mvn -Pjlink package`. (Note: references a stale `app` module; reconcile or retire.)

Replace each `mvn` call with its existing Gradle equivalent — all the building blocks already
exist: `:cli:jlinkImage_<platform>` (Task 14), `assembleClaudeProd` / `assembleGeminiProd` /
`assembleWindows` (Tasks 23/24/25), and `PackageRuntime`/`ValidateRelease` (Task 16).

**Version bump has no Gradle home** and is the crux. Maven's `mvn versions:set` writes the
version into the poms; Gradle's render reads `plugin.version` from `gradle.properties`
(`RenderSpec.kt:33`, `claude/build.gradle.kts:9`). So the bump must become "rewrite
`plugin.version` in `gradle.properties` and commit it" — and that becomes the **single source
of truth** for the build version post-cutover. Decide where the bump logic lives (a small
Gradle task, a helper invoked by `PublishRelease`, or inline in the scripts) — but there must
be exactly one writer of the version.

**jq stamp may become redundant.** The scripts post-stamp `plugin.json` /
`gemini-extension.json` via `jq` because the *Maven* build did not always carry the release
version into the manifest. The Gradle manifest tasks already `expand()` `plugin.version` into
the manifest at build time (`claude/build.gradle.kts:18`). Confirm whether the jq step is now a
no-op; if so, drop it (this is the "modulo jq stamp" caveat the assembly tasks reference —
resolve it here, don't carry it past cutover).

Prefer making `PublishRelease.java` the Gradle-native entrypoint (it is the documented one) and
either retiring the shell scripts or thinning them to call it, rather than maintaining two
divergent release paths.

Acceptance: a **dry-run** of the full release (claude runtime zip + gemini extension + windows
payload) runs end-to-end with **zero `mvn` invocations** — verified by `grep -rn 'mvn ' ` over
`PublishRelease.java` and `devtools/scripts/*.sh` returning nothing — *while the poms still
exist*; the dry-run output (payloads + runtime zip) is byte/behaviour-equivalent to the
Maven-built release; a deliberate version bump updates `gradle.properties` and stamps the
manifests correctly.

### Task 17: Full parity sign-off + remove `pom.xml` files [High]

*Depends-on: 16, 21, 22, 23, 24, 25, 26*

Diff **all payloads** — the five assembled variants (claude-dev, gemini-dev, claude-prod,
gemini-prod, windows via Tasks 21–25) plus the `runtime-<ver>/` zip (Task 16) — Gradle vs Maven
on a clean tree. Update `DEVELOPMENT.md`, `devtools/scripts/smoke-gemini.sh`, the release
scripts/docs (Task 26 does the release-path code; this task updates the surrounding docs/CI),
and any CI to Gradle tasks. Leave `docs/proposals/*.md` as historical migration narrative.
**Only after sign-off**, remove all **nine** `pom.xml` files (root reactor + `skills/`
aggregator + `core`, `cli`, `skills/pkg`, `claude`, `gemini`, `packaging`, `devtools`) in a
single cutover commit. Tag the result.

Acceptance: all five payloads parity-clean; no remaining `mvn` invocation anywhere except
`docs/proposals/*.md`; `./gradlew build` green from a clean checkout with **no `pom.xml`
present**.

### Task 19: Run the TS tests in the build [Medium]

*Depends-on: 17*

Added at `plan-71-v5`. The `skills/pkg/scripts` TS suite (`session-start.test.ts` — e.g. the
`jlinkDir` non-directory / install-from-jlinkDir cases) is currently run only by a manual
`npm test` (`package.json` `test` script: `tsc -p tsconfig.test.json && npm run bundle-test &&
node --test`). Neither Maven nor Gradle ran it in the build (Maven only did `npm install` +
`npm run build`), so it was never a parity gap — but post-cutover there is no Maven home for it
either, and an unrun test rots. Wire it into the Gradle build so `./gradlew check` (or `build`)
executes the TS suite.

Add a Gradle task (e.g. an `NpmTask`/`Exec` running the `package.json` `test` script, or its
`tsc`+`node --test` steps) in `skills/pkg/build.gradle.kts`, with `node_modules`/`tasks` as
inputs so it's incremental, and hook it into `check`. Decide whether a TS-test failure should
fail the aggregate build (recommended: yes).

Acceptance: `./gradlew :skills:pkg:check` runs the TS tests and fails on a deliberately broken
TS test; a clean run is green.
