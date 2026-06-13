# Plan 79 — Split `skills:pkg` into `plugin-model`, `skills:render`, and `plugin-resources`

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

## Goal — target module layout (Option C: three modules)

Top-level modules: `plugin-model` and `plugin-resources` move OUT of `skills/`.
The `skills/` folder stays the human-editable home: the `.jte.md` content lives
directly under it (`start/`, `experimental/`, `shared/`) so a contributor who only
wants to edit skill text opens `skills/` and sees just content + the one render
module. `skills/pkg` is **renamed to `skills/render`** (the `:skills:render`
Gradle module) and shrinks to skill-rendering only.

```
shipsmooth/
├── core/  cli/  packaging/
├── plugin-model/            <- NEW, top-level (leaf, tiny). Os, Platform, Env,
│                               PluginModel. No dependency on any other module.
├── plugin-resources/        <- NEW, top-level. Renders "the rest": Target,
│                               HooksRenderer, SessionStartConfigRenderer,
│                               scripts/ TS hook, install-shipsmooth.sh, and the
│                               render*/copyDist* tasks. Deps: plugin-model + skills:render.
└── skills/                  <- human-editable content home (NOT a module itself)
    ├── start/  experimental/  shared/   <- .jte.md content (UNCHANGED location)
    └── render/              <- was skills/pkg. The :skills:render module:
                                SkillRenderer + JTE staging/generation only.
                                Dep: plugin-model. Stages ../start ../experimental
                                ../shared (the existing `dir("..")` path still
                                resolves — render/ sits at the same depth pkg/ did).
```

`packaging` depends on `plugin-model` (not `skills:pkg`/`skills:render`) for `Os`.

### Why three modules and not two

The constraint is that `Os`, `Platform`, `Env`, `PluginModel` are **shared leaf
types**: `SkillRenderer` needs `PluginModel`; `PluginModel` needs `Os`/`Env`;
`HooksRenderer`/`SessionStartConfigRenderer`/`Target` need all of them; and
`packaging`'s `PackageRuntime` needs `Os`. Whichever module holds these leaves is
upstream of everyone.

A two-module split forces a bad trade-off:
- Put the leaves in `skills:render` (so `plugin-resources -> skills:render`): then
  `skills:render` renders only the skill ✅ but `packaging` must still depend on
  `skills:render` to reach `Os` ❌ — the original smell survives.
- Put the leaves in `plugin-resources` (so `skills:render -> plugin-resources`):
  then `packaging` is clean ✅ but `Target` and the leaves live in
  `plugin-resources` while `skills:render` becomes downstream — and it no longer
  cleanly "renders only the skill" relative to the shared infra.

Extracting the four leaf types into a tiny `plugin-model` module satisfies **all**
goals with **no dependency cycle**, in one strict direction:

```
plugin-model  <-  skills:render  <-  plugin-resources
       ^---------------------------------/   (plugin-resources also deps plugin-model)
plugin-model  <-  packaging
```

`skills:render` renders only the skill. `plugin-resources` renders the rest and
*refers to* `skills:render` (its `Target` constructs `SkillRenderer`). `packaging`
depends only on the tiny `plugin-model`. Cost: one extra small module — accepted.

### Class placement

| Class / asset | Target module |
|---|---|
| `Os`, `Platform`, `Env`, `PluginModel` (+ their unit tests) | `plugin-model` |
| `SkillRenderer` (+ `SkillVariant` if separate), JTE staging/generation | `skills:render` (at `skills/render`) |
| `.jte.md` content (`start/`, `experimental/`, `shared/`) | stays at `skills/` (staged by `skills:render` via `../`) |
| `Target` (+ `TargetTest`, `TargetIntegrationTest`) | `plugin-resources` |
| `HooksRenderer`, `SessionStartConfigRenderer` | `plugin-resources` |
| `scripts/` TS hook + npm/tsc/test wiring; `install-shipsmooth.sh` + lint; `PosixBootstrapIntegrationTest` | `plugin-resources` |
| `render*` + `copyDist*` Gradle tasks (run `Target`) | `plugin-resources` |

### Visibility consequence

After the split these types cross module boundaries (package stays
`io.bitken.ss.resources` everywhere, so imports don't churn). `PluginModel`,
`Env`, `Os` are already `public`; `Platform` is package-private and must become
`public` (`Target` calls `Platform.from`). `SkillRenderer` must become reachable
from `Target` in `plugin-resources` — make `SkillRenderer` (and the
constructor/methods `Target` uses) `public`. Likewise any `HooksRenderer` /
`SessionStartConfigRenderer` members `Target` invokes stay co-located with
`Target` in `plugin-resources`, so they can remain package-private.

### Module naming note

`skills/pkg` is **renamed to `skills/render`**; the Gradle path changes
`:skills:pkg` -> `:skills:render`. All consumer references
(`project(":skills:pkg")`, `evaluationDependsOn(":skills:pkg")`,
`skillsPkg.tasks.named(...)`) update accordingly. The `.jte.md` content does NOT
move — it stays at `skills/{start,experimental,shared}`, kept at the top of
`skills/` for human editors; `skills:render` continues to stage it via
`projectDirectory.dir("..")`, which still resolves because `render/` sits at the
same depth `pkg/` did. (The `render*`/`copyDist*` tasks themselves move to
`plugin-resources` — see Task 4 — so `skills:render` ends up with only the JTE
staging/generation + `SkillRenderer`.)

## Integration test strategy

End-to-end proof that the split preserves byte-identical plugin output:

1. **Parity integration test** — build a prod payload (e.g. `assembleClaudeProd`)
   on this branch and diff the rendered `skills/`, `hooks/`, `dist/`, and
   `.claude-plugin/` trees against a baseline captured from `main`
   (pre-split). Per memory [Migration coverage = parity not 95%], the bar for
   moved code is byte-parity, not a coverage number.
2. **Cross-module reachability test** — a `packaging` test that constructs/uses
   `io.bitken.ss.resources.Os` proving `packaging -> plugin-model` resolves
   without `skills:render`/`plugin-resources` on its classpath.

These are written failing first (the new modules don't exist yet), per Core
Invariant #6, then made green by the split.

## Risk-calibrated task list

Risk-sorted (High → Low); a Low dependency that blocks a High task precedes it.

### Task 1: Create `plugin-model` leaf module + move shared types [High]

Create `plugin-model/` with `build.gradle.kts` (java-conventions; jackson if any
type needs it — `PluginModel` is a plain record, likely none). Move `Os.java`,
`Platform.java`, `Env.java`, `PluginModel.java` (+ `OsTest`, `PlatformTest`,
`EnvTest`, `PluginModelTest`) from `skills/pkg/...` to top-level `plugin-model/`,
same package. Make `Platform` public. Register `plugin-model` in
`settings.gradle.kts`. High: establishes the new leaf boundary + forces
public-visibility changes; everything depends on it.

### Task 2: Rename `skills/pkg` -> `skills/render`, reduce it to skill-rendering only [High]

*Depends-on: 1*

Rename the `skills/pkg` module dir to `skills/render` and its Gradle path
`:skills:pkg` -> `:skills:render` in `settings.gradle.kts`. Reduce it to contain
**only**: `SkillRenderer.java` (+ `SkillVariant`) and the JTE staging/generation
Gradle wiring. The `.jte.md` content stays put at
`skills/{start,experimental,shared}` — confirm the `projectDirectory.dir("..")`
staging still resolves from `render/`. Add `implementation(project(":plugin-model"))`.
Make `SkillRenderer` + the constructor/methods `Target` will call `public`. Verify
the skill renders against the now-cross-module `PluginModel`. High: proves the
JTE-precompiled `SkillRenderer` compiles and runs across the module boundary, and
that the content-staging path survives the rename.

### Task 3: Create `plugin-resources`; move Target + non-skill renderers + scripts + installer [High]

*Depends-on: 1,2*

Create top-level `plugin-resources/` with `build.gradle.kts` (java-conventions,
jackson, node-gradle). Add deps `implementation(project(":plugin-model"))` and
`implementation(project(":skills:render"))`. Move `Target.java` (+ `TargetTest`,
`TargetIntegrationTest`), `HooksRenderer.java`, `SessionStartConfigRenderer.java`,
the `scripts/` TS/npm pipeline (+ its node-gradle wiring + `compileTs`/`testTs`),
and `install-shipsmooth.sh` (+ `lintInstallScript`, `PosixBootstrapIntegrationTest`).
`Target` here constructs `SkillRenderer` (from `:skills:render`) + the local
Hooks/Config renderers. High: the orchestrator + the fragile TS/installer wiring
land here and must compile against two upstream modules.

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

Run the parity integration test against the `main` baseline for claude-prod (and
at least gemini-prod) payloads; confirm byte-identical. Update the stale Phase-5
target-state comment in `settings.gradle.kts`, and any docs (`DEVELOPMENT.md`,
build proposals) describing the old module layout. Low: verification +
documentation, no behavior change.

## Out of scope

- Moving the `.jte.md` content out of `skills/` (it deliberately stays at the top
  of `skills/` for human editors; only the renderer lives in `skills/render`).
- Any change to `PackageRuntime`/`ValidateRelease`/`PublishRelease` logic.
- Cutting a release (`publishRelease` is never run by this plan).
