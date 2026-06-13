# Plan 79 — Split `skills:pkg` into `plugin-model` + `skills:pkg` (reduced) + `plugin-resources`, then regroup hosts under `harness/`

> **v7 (2026-06-13):** the host group was renamed `targets/` -> `harness/`. The
> folder holds only agent harnesses (claude/gemini/codex; opencode, pi planned),
> so a precise name wins over a generic one — mirroring how `cli` was extracted as
> its own concern. Singular `harness/` is kept short. IDE/editor extensions, if
> added later, get their own top-level folder rather than joining this one (so
> `harness/` never has to lie about its contents); a `harness -> targets`
> re-broadening later is cheap if that forecast changes. Mechanical rename only:
> Gradle paths `:targets:*` -> `:harness:*`, dir `targets/` -> `harness/`; the Java
> package stays `io.bitken.ss.resources`; golden diff re-verified byte-identical
> across all 4 prod variants. Task 7/8 bodies below are preserved as written (when
> the group was `targets/`); read "targets" there as the prior name for "harness".

## Context

The `skills/` tree currently conflates unrelated concerns under one Gradle
subproject (`skills:pkg`):

1. **Skill content** — the `.jte.md` source under `skills/start`, `skills/experimental`,
   `skills/shared` (~85 files). Pure prompt/workflow material; the input to rendering.
2. **A plugin-build engine** — `skills/pkg/`: Java renderers (`Target`,
   `SkillRenderer`, `HooksRenderer`, `SessionStartConfigRenderer`), shared infra
   (`Os`, `Platform`, `Env`, `PluginModel`), a TypeScript SessionStart hook under
   `scripts/`, the POSIX bootstrap `install-shipsmooth.sh`, and all the Gradle
   wiring (JTE staging/generation, npm/tsc pipeline, `render*` + `copyDist*` tasks).

Most of `skills:pkg` is **not** skills. Only `SkillRenderer` + the `.jte`
content + JTE generation render the skill file itself. `HooksRenderer`,
`SessionStartConfigRenderer`, the TS hook, and the installer render/produce
"the rest" of the plugin payload. `Os/Platform/Env/PluginModel` are shared leaf
types that everything builds on.

The misleading layout also produces a real smell: `packaging` depends on
`:skills:pkg` **only** to reach `io.bitken.ss.resources.Os` (used by
`PackageRuntime`) — i.e. it pulls in the entire skills-rendering module for one
enum.

A prior migration (plan-71 Tasks 21/23) already moved the payload JS copies out
of `packaging` into `skills:pkg`, so **`packaging` has no leftover "scripts" to
relocate** — it is now legitimately a runtime/release module (`PackageRuntime`,
`ValidateRelease`, `PublishRelease`, `ReleaseGuard`). This plan therefore does
**not** touch packaging beyond repointing its one dependency.

## Goal — target module layout

Final layout after Tasks 1–8. The three-module split (Tasks 1–6) extracts a leaf
types module and reduces `skills:pkg` to skill-rendering; the `harness/` regrouping
(Tasks 7–8, added v6 as `targets/`, renamed `harness/` in v7) clusters the per-host
plugin modules and the shared renderer they all drive. `plugin-model` and `skills/`
stay top-level — `plugin-model` is a shared leaf (packaging depends on it too, not
host-specific), and `skills/` evolves at a different pace/frequency than the host
integrations.

The group holds only agent harnesses (claude/codex, the gemini CLI extension; with
opencode and pi planned), so `harness/` names exactly what's there — the set is
growing (at 2 hosts plan-68 flattened them; at 5 a group wins). IDE/editor
extensions (e.g. cursor), if added later, get their own top-level folder rather than
joining `harness/`.

```
shipsmooth/
├── core/  cli/  packaging/
├── plugin-model/            <- leaf, tiny. Os, Platform, Env, PluginModel.
│                               No dependency on any other module. (Tasks 1)
├── harness/                 <- per-host plugin modules + the shared renderer they
│   │                           drive (NOT a module itself). (Tasks 7-8, v6; renamed
│   │                           from targets/ in v7)
│   ├── shared/              <- was plugin-resources. Renders "the rest": Target,
│   │                           HooksRenderer, HookCommandRenderer,
│   │                           SessionStartConfigRenderer, scripts/ TS hook,
│   │                           install-shipsmooth.sh, render*/copyDist* tasks.
│   │                           Deps: plugin-model + skills:pkg.  (:harness:shared)
│   ├── claude/             <- was claude/.   (:harness:claude)
│   ├── gemini/             <- was gemini/.   (:harness:gemini)
│   └── codex/              <- was codex/.    (:harness:codex)
└── skills/                  <- human-editable content home (NOT a module itself)
    ├── start/  experimental/  shared/   <- .jte.md content (UNCHANGED location)
    └── pkg/                <- :skills:pkg (UNCHANGED path). SkillRenderer + JTE
                                staging/generation only. Dep: plugin-model. Still
                                stages ../start ../experimental ../shared via the
                                existing `dir("..")` path (unchanged).
```

Dependency direction (unchanged by the regrouping, paths only):
`plugin-model <- skills:pkg <- harness:shared <- harness:{claude,gemini,codex}`;
`plugin-model <- packaging`.

`packaging` depends on `plugin-model` (not `skills:pkg`) for `Os`.

### Why three modules and not two

The constraint is that `Os`, `Platform`, `Env`, `PluginModel` are **shared leaf
types**: `SkillRenderer` needs `PluginModel`; `PluginModel` needs `Os`/`Env`;
`HooksRenderer`/`SessionStartConfigRenderer`/`Target` need all of them; and
`packaging`'s `PackageRuntime` needs `Os`. Whichever module holds these leaves is
upstream of everyone.

A two-module split forces a bad trade-off:
- Put the leaves in `skills:pkg` (so `plugin-resources -> skills:pkg`): then
  `skills:pkg` renders only the skill ✅ but `packaging` must still depend on
  `skills:pkg` to reach `Os` ❌ — the original smell survives.
- Put the leaves in `plugin-resources` (so `skills:pkg -> plugin-resources`):
  then `packaging` is clean ✅ but `Target` and the leaves live in
  `plugin-resources` while `skills:pkg` becomes downstream — and it no longer
  cleanly "renders only the skill" relative to the shared infra.

Extracting the four leaf types into a tiny `plugin-model` module satisfies **all**
goals with **no dependency cycle**, in one strict direction:

```
plugin-model  <-  skills:pkg  <-  plugin-resources
       ^------------------------------/   (plugin-resources also deps plugin-model)
plugin-model  <-  packaging
```

`skills:pkg` renders only the skill. `plugin-resources` renders the rest and
*refers to* `skills:pkg` (its `Target` constructs `SkillRenderer`). `packaging`
depends only on the tiny `plugin-model`. Cost: one extra small module — accepted.

### Class placement

| Class / asset | Target module |
|---|---|
| `Os` (pure facts only — see below), `Platform`, `Env`, `PluginModel` (+ their pure-fact unit tests) | `plugin-model` |
| `SkillRenderer` (+ `SkillVariant` if separate), JTE staging/generation | `skills:pkg` (unchanged, at `skills/pkg`) |
| `.jte.md` content (`start/`, `experimental/`, `shared/`) | stays at `skills/` (staged by `skills:pkg` via `../`) |
| `Target` (+ `TargetTest`, `TargetIntegrationTest`) | `plugin-resources` |
| `HooksRenderer`, `SessionStartConfigRenderer`, **new `HookCommandRenderer`** | `plugin-resources` |
| **The hook-command + companion-file emission** (was `Os.hookCommand`/`copyResource`); `install-shipsmooth.sh` + lint; `install-runtime.bat` generation; `PosixBootstrapIntegrationTest` + the `hookCommand`/`copyResource` cases of `OsTest` | `plugin-resources` |
| `scripts/` TS hook + npm/tsc/test wiring | `plugin-resources` |
| `render*` + `copyDist*` Gradle tasks (run `Target`) | `plugin-resources` |

### `Os` decoupling (discovered building Task 1 — v5)

`Os.Posix.hookCommand` read `install-shipsmooth.sh` off its OWN classpath
(`Os.class.getResourceAsStream`), and `Os.Windows.hookCommand` wrote
`install-runtime.bat` inline — i.e. `hookCommand` is a *renderer that emits a
companion file*, not a pure value-type method. That makes `Os` unable to live in a
pure-types `plugin-model` while the script lives in `plugin-resources` (the leaf
can't depend on the script's module). Decision (human): the script is a plugin
**resource** and belongs in `plugin-resources`; the code that emits it moves there
too. So:
- `Os` (in `plugin-model`) keeps only pure facts: `launcherFileName`, `javaExe`,
  `cliBinPath`, `from`, `fromPackagingTarget`, and the `Posix`/`Windows`
  discriminants. It loses `hookCommand`, `copyResource`, `INSTALL_SCRIPT_NAME`,
  and the `.bat` helpers.
- A new `HookCommandRenderer` in `plugin-resources` owns the hook command string +
  companion-file emission, branching on `os instanceof Os.Posix/Windows`. It holds
  `install-shipsmooth.sh` as its resource and generates `install-runtime.bat`.
  `HooksRenderer` calls it (replacing the old `model.os().hookCommand(...)`).
- This is the only behavioral-shape change in the plan; the rendered output stays
  byte-identical (the golden diff is the guard).

### Visibility consequence

All moved types keep package `io.bitken.ss.resources`, so imports don't churn.
Verified building Task 1: `Os`, `Platform`, `Env`, `PluginModel` are ALL already
`public` (`Platform`/`Os` are public sealed interfaces) — no visibility changes
needed there. `SkillRenderer` must become reachable from `Target` in
`plugin-resources` — make `SkillRenderer` (and the constructor/methods `Target`
uses) `public`. `HooksRenderer` / `SessionStartConfigRenderer` /
`HookCommandRenderer` stay co-located with `Target` in `plugin-resources`, so they
remain package-private.

### Module naming note

`skills/pkg` **keeps its name and Gradle path** (`:skills:pkg`). Rationale: the
`pkg` suffix already signals "code, not editable content" (the property we care
about), and keeping it avoids churning the ~17 consumer references
(`project(":skills:pkg")`, `evaluationDependsOn(":skills:pkg")`,
`skillsPkg.tasks.named(...)`). A `render` rename was considered and rejected — the
genuine packaging code (`Target`, `render*`/`copyDist*` tasks, `scripts/`,
installer) all leaves for `plugin-resources`, so the rename would buy little
precision at real churn cost. The `.jte.md` content does NOT move — it stays at
`skills/{start,experimental,shared}` for human editors; `skills:pkg` keeps staging
it via the unchanged `projectDirectory.dir("..")` path. (The `render*`/`copyDist*`
tasks themselves move to `plugin-resources` — see Task 4 — so `skills:pkg` ends up
with only the JTE staging/generation + `SkillRenderer`.)

## Verification strategy

This plan is a pure refactor — classes move between modules with **zero intended
behavior change** — so the primary verification is a **golden before/after output
diff**, not unit-test coverage (coverage is explicitly out of scope for this plan,
per human direction). A diff of the real assembled payloads is a stronger and more
honest check that "nothing rendered changed" than any line-coverage number.

1. **Golden payload diff (primary).** BEFORE any code moves, build the prod
   payloads on a clean `main` checkout and snapshot them into
   `.agents/tmp/baseline/` (per memory [Use .agents/tmp for temp files]). Variants:
   `assembleClaudeProd`, `assembleGeminiProd`, `assembleCodexProd`,
   `assembleWindows` — this set exercises every `Os`/`Platform` branch and the
   installer path. (Dev variants are skipped: they pull the host-specific jlink
   image and re-exercise the same renderers.) AFTER the refactor (at minimum after
   Task 4, when wiring settles), rebuild the same variants on the branch and
   `diff -r` each against its baseline. **Empty diff = behavior preserved.** Any
   diff is a real regression to investigate before closeout.
2. **Cross-module reachability test (kept).** A small `packaging` test that
   constructs/uses `io.bitken.ss.resources.Os`, proving `packaging -> plugin-model`
   resolves without `skills:pkg`/`plugin-resources` on its classpath. A diff can't
   catch `packaging` silently regaining a `skills:pkg` dependency; this asserts the
   structural fact. Written failing first (Task 5), green after the dep repoint.

The integration-test preamble below therefore consists of (a) capturing the golden
baseline and (b) the reachability test (red first), rather than JUnit parity tests.

## Risk-calibrated task list

Risk-sorted (High → Low); a Low dependency that blocks a High task precedes it.

### Task 1: Create `plugin-model` leaf module + move shared types [High]

Create top-level `plugin-model/` with `build.gradle.kts` (java-conventions; no
jackson — the four types reference only `java.*`). Move `Os.java`, `Platform.java`,
`Env.java`, `PluginModel.java` (+ `OsTest`, `PlatformTest`, `EnvTest`,
`PluginModelTest`) from `skills/pkg/...` to `plugin-model/`, same package. Register
`plugin-model` in `settings.gradle.kts`. **Decouple `Os`**: strip `hookCommand`,
`copyResource`, `INSTALL_SCRIPT_NAME`, and the `.bat` helpers from `Os` (they move
to `plugin-resources` in Task 3 as `HookCommandRenderer`); `Os` keeps only pure
facts. Move the `hookCommand`/`copyResource` cases out of `OsTest` (they go to
Task 3); keep the pure-fact cases. (No visibility changes — all four types are
already public.) High: establishes the new leaf boundary and the `Os` decoupling;
everything depends on it.

### Task 2: Reduce `skills:pkg` to skill-rendering only [High]

*Depends-on: 1*

`skills/pkg` keeps its name and `:skills:pkg` Gradle path. Reduce it to contain
**only**: `SkillRenderer.java` (+ `SkillVariant`) and the JTE staging/generation
Gradle wiring. The `.jte.md` content stays put at
`skills/{start,experimental,shared}`; the existing `projectDirectory.dir("..")`
staging is unchanged. Add `implementation(project(":plugin-model"))`. Make
`SkillRenderer` + the constructor/methods `Target` will call `public`. Verify the
skill renders against the now-cross-module `PluginModel`. High: proves the
JTE-precompiled `SkillRenderer` compiles and runs across the module boundary.

### Task 3: Create `plugin-resources`; move Target + non-skill renderers + scripts + installer [High]

*Depends-on: 1,2*

Create top-level `plugin-resources/` with `build.gradle.kts` (java-conventions,
jackson, node-gradle). Add deps `implementation(project(":plugin-model"))` and
`implementation(project(":skills:pkg"))`. Move `Target.java` (+ `TargetTest`,
`TargetIntegrationTest`), `HooksRenderer.java`, `SessionStartConfigRenderer.java`,
the `scripts/` TS/npm pipeline (+ its node-gradle wiring + `compileTs`/`testTs`),
and `install-shipsmooth.sh` (+ `lintInstallScript`, `PosixBootstrapIntegrationTest`).
**Add `HookCommandRenderer`** (new class): it absorbs the hook-command +
companion-file logic stripped from `Os` in Task 1 — branches on
`os instanceof Os.Posix/Windows`, holds `install-shipsmooth.sh` as its resource
(`getResourceAsStream`), and generates `install-runtime.bat`. Move the
`hookCommand`/`copyResource` test cases from `OsTest` here (retargeted at
`HookCommandRenderer`). `HooksRenderer` calls `HookCommandRenderer` instead of
`model.os().hookCommand(...)`. `Target` constructs `SkillRenderer` (from
`:skills:pkg`) + the local Hooks/Config/HookCommand renderers. High: the
orchestrator + the fragile TS/installer wiring + the `Os` decoupling land here and
must compile against two upstream modules; the golden diff guards byte-parity.

### Task 4: Move render*/copyDist* tasks to `plugin-resources`; repoint consumers [Medium]

*Depends-on: 3*

Move the `render*` and `copyDist*`/`copyDistProd` task registrations (they run
`Target`) into `plugin-resources/build.gradle.kts`. Update the claude/gemini/codex
consumer build scripts: `project(":skills:pkg")` /
`evaluationDependsOn(":skills:pkg")` / `skillsPkg.tasks.named("renderClaudeProd")`
etc. now point at `:plugin-resources`. Confirm all ~17 cross-module task
references across the three consumers resolve. Medium: mechanical but spread
across three consumer build scripts + buildSrc helper references.

### Task 5: Repoint `packaging` dependency to `plugin-model` [Low]

*Depends-on: 1*

Change `packaging/build.gradle.kts`
`implementation(project(":skills:pkg"))` → `implementation(project(":plugin-model"))`.
Add the cross-module reachability test. `PackageRuntime` only uses `Os`, now in
`plugin-model`. Low: one-line dep swap + test, isolated.

### Task 6: Parity verification + docs/settings cleanup [Low]

*Depends-on: 4,5*

Rebuild `assembleClaudeProd`, `assembleGeminiProd`, `assembleCodexProd`,
`assembleWindows` on the branch and `diff -r` each against the `.agents/tmp/baseline/`
snapshot captured pre-split; confirm every diff is empty (byte-identical). Update
the stale Phase-5 target-state comment in `settings.gradle.kts`, and any docs
(`DEVELOPMENT.md`, build proposals) describing the old module layout. Low:
verification + documentation, no behavior change.

### Task 7: Move `plugin-resources` -> `targets/shared` [Medium]

*Depends-on: 4*

Added v6 (post-3-module-split regrouping). `git mv plugin-resources targets/shared`;
Gradle path `:plugin-resources` -> `:targets:shared` in `settings.gradle.kts`. Fix
the now-deeper relative paths in its build script: `repoRoot` goes from `dir("..")`
to `dir("../..")` (the windows jlink path and the `repoRoot.dir("build")` payload
default depend on it). Repoint the claude/gemini/codex consumers'
`project(":plugin-resources")` / `evaluationDependsOn(":plugin-resources")` to
`:targets:shared`. The Java package stays `io.bitken.ss.resources` (no rename —
module name need not match package). Medium: relative-path fixes are the risk; the
golden diff guards the windows jlink path especially.

### Task 8: Move `claude`/`gemini`/`codex` -> `targets/{claude,gemini,codex}` [Medium]

*Depends-on: 7*

`git mv claude targets/claude` (and gemini, codex); Gradle paths `:claude` ->
`:targets:claude` etc. in `settings.gradle.kts`. Update: each module's dep on
`:targets:shared` (relative paths to repo root deepen by one — most use
`rootProject.layout`, which is safe; audit any `layout.projectDirectory`-relative
repo-root walks). The claude nested `GradleBuild` hard-codes
`:claude:assembleClaudeDev` -> `:targets:claude:assembleClaudeDev`. `assemble*` task
NAMES are unchanged. Re-run the full golden diff (now via `:targets:claude:assembleClaudeProd`
etc.) — all 4 variants must stay byte-identical. Update DEVELOPMENT.md + settings
comment to the `targets/` layout. Medium: spread across three modules + the nested
build; output-dir defaults (`build-codex-dev` etc.) resolve via `rootProject` so
they survive, but must be verified.

## Out of scope

- Moving the `.jte.md` content out of `skills/` (it deliberately stays at the top
  of `skills/` for human editors; only the renderer lives in `skills/pkg`).
- Any change to `PackageRuntime`/`ValidateRelease`/`PublishRelease` logic.
- Cutting a release (`publishRelease` is never run by this plan).
