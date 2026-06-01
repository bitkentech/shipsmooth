# Plan 61: Replace stringly-typed BuildProfile/PluginModel with typed build-target axes

## Context

Backlog reference: `local: typed-build-target-axes` — structural refactor of the plugin
build's platform/env modelling.

The plugin build derives all platform/env behaviour from two records that carry the
target as raw `String`s and answer questions about it with string comparisons:

- `BuildProfile` (`integrations/common/.../resources/BuildProfile.java`) holds
  `platform`, `env`, `basePluginName` as strings and exposes `isDev()`, `isGemini()`,
  `isWindows()`, plus a `cliBin(version)` that hardcodes the Windows
  `%LOCALAPPDATA%\...\shipsmooth.bat` path inside an `if (isWindows())` branch.
- `PluginModel` carries `platform` as a `String` and re-derives Windows knowledge with
  `isWindows()`, `windowsCacheRoot()`, `windowsRuntimeDest()` — methods that are
  meaningless (but still callable) for non-Windows targets.

This raw-string platform leaks across consumers:

- `HooksRenderer` branches with `if (!model.isWindows())` and reaches into
  `windowsCacheRoot()` / `windowsRuntimeDest()` to assemble the runtime-install command
  (tell-don't-ask violation).
- The JTE templates branch in **9 places** with `@if("gemini".equals(model.platform()))`
  to choose between parallel `skills/start/claude/*` and `skills/start/gemini/*`
  fragment directories.
- `PackageRuntime` (in the separate `packaging` module) **independently** re-derives
  Windows-ness with `target.startsWith("win32")` and builds the launcher as
  `bin/shipsmooth.cmd`.

### The five real variants

Taken from the root `pom.xml` profiles (not a theoretical cross-product):

| Profile id   | platform | env  |
|--------------|----------|------|
| `dev`        | claude   | dev  |
| `prod`       | claude   | prod |
| `windows`    | windows  | prod |
| `gemini-dev` | gemini   | dev  |
| `gemini`     | gemini   | prod |

These decompose into three axes that are **not** a free cross-product:

- **Agent platform** (`claude` | `gemini`) — chooses skill fragments, cache subdir,
  hook flavour. Gemini has no OS variant today.
- **OS** (`posix` | `windows`) — chooses launcher name, cache-root path shape, java
  binary name. `windows` is really *claude-on-windows*: `(platform=claude, os=windows)`,
  not a third peer platform.
- **Env** (`dev` | `prod`) — uniform across all platforms; only flips the `-dev` suffix
  on plugin/skill names.

### Bug surfaced by this analysis

`BuildProfile.cliBin()` tells the agent the Windows launcher is `shipsmooth.bat`, while
`PackageRuntime` actually writes `shipsmooth.cmd`. The path the skill instructs the agent
to invoke does not match the file packaging creates. A single source of truth for the
launcher name eliminates this class of bug.

## Goals

1. Parse raw build properties into a typed build target **once**, at the boundary, then
   never compare platform strings again.
2. Make illegal variants (e.g. gemini-on-windows) unrepresentable, and make
   OS-specific methods uncallable on non-matching targets.
3. Push platform-varying behaviour onto the types (tell-don't-ask), so renderers stop
   branching.
4. Expose the OS axis as a type the `packaging` module can consume, killing the
   `.bat`/`.cmd` divergence by construction.
5. Keep sealed-type exhaustiveness so adding a future platform/OS forces the compiler to
   flag every unhandled rendering branch.

## Non-goals

- No change to the rendered SKILL.md / hooks.json / session-start-config.json **output**
  for the existing five variants. This is a behaviour-preserving refactor; the
  integration test (`ResourceBuilderIntegrationTest`) output must be byte-identical
  except for the deliberate `.bat` → `.cmd` launcher-name correction (Task 5).
- No premature `OsConventions`-style "common" extraction. The shared OS type emerges from
  Task 4/6; we do not pre-factor a generic module.
- No new build profiles. `(claude, windows, dev)` becomes *representable* but we add no
  pom profile for it.

## Design

The variant becomes a `Target` value composed of three typed axes. `Target.from(platform,
env)` is the single boundary that turns build-property strings into types and encodes
which combinations are legal. `BuildProfile` is absorbed into `Target` + `Env`;
`PluginModel` keeps plugin identity and delegates every platform/OS question to its
`Target`.

```
Target(platform: Platform, os: Os, env: Env)
   Platform  = sealed Claude | Gemini      (agent axis)
   Os        = sealed Posix  | Windows     (OS axis; consumed by packaging too)
   Env       = enum  DEV | PROD            (suffix axis)
```

**Execution order (risk-sorted, dependency-respecting): 1 → 2 → 3 → 4 → 5 → 7 → 6 → 8.**
The dependency DAG (`1 → 2,3 → 4 → 5 → {6,7} → 8`) is nearly linear, so the topological
order is essentially forced. The only risk-sort freedom is at the `{6,7}` fan-out: both
depend solely on Task 5, so the High-risk Task 7 (PluginModel cutover) runs before the
Medium-risk Task 6 (packaging). Task headings keep their canonical integer IDs because
the dependency references and the task CLI rely on them — execute in the order above, not
in heading order.

### Task 1: Introduce Env enum and fold suffix logic into it [Low]

Add `Env` enum (`DEV`, `PROD`) in `io.bitken.ss.resources` with `suffix()` (`"-dev"` /
`""`) and `decorate(String base)` (returns `base + suffix()`). Add a unit test
`EnvTest`. This is the leaf with no dependencies — it replaces `BuildProfile.isDev()` and
the three `isDev() ? base + "-dev" : base` ternaries, but does not yet wire into
`BuildProfile` (Task 7 does the cutover). Pure addition, nothing else changes yet.

### Task 2: Introduce sealed Os (Posix, Windows) owning path/launcher shape [Medium]

*Depends-on: 1*

Add `sealed interface Os permits Posix, Windows` with methods:

- `launcherFileName()` → `"shipsmooth"` / `"shipsmooth.cmd"` (single source of truth)
- `javaExe()` → `"java"` / `"java.exe"`
- `cliBinPath(String pluginName, String version, String cacheSubdir)` — the posix
  `${XDG_CACHE_HOME:-~/.cache}/<cacheSubdir>/runtime-<version>/bin/shipsmooth` vs the
  windows `%LOCALAPPDATA%\<pluginName>\<version>\runtime\bin\<launcherFileName>` form,
  lifted verbatim from `BuildProfile.cliBin()`.

Add `OsTest` asserting both implementations produce the exact strings the current
`BuildProfile.cliBin()` produces (guard against output drift). The Windows method must
emit `shipsmooth.cmd`, matching `PackageRuntime`, and a test pins this so Task 5 can rely
on it.

### Task 3: Introduce sealed Platform (Claude, Gemini) owning agent-axis behaviour [Medium]

*Depends-on: 1*

Add `sealed interface Platform permits Claude, Gemini` with:

- `id()` → `"claude"` / `"gemini"`
- `skillFragmentDir()` → `"start/claude"` / `"start/gemini"` (replaces the 9 template
  `@if("gemini".equals(...))` branches in a later step — Task 8)
- `cacheSubdir(String basePluginName, Env env)` — moves `BuildProfile.cacheSubdir()` here

Add `PlatformTest`. Pure addition; not yet wired.

### Task 4: Compose axes into Target with a boundary factory [High]

*Depends-on: 2,3*

Add `record Target(Platform platform, Os os, Env env)` with the static factory
`Target.from(String platformProp, String envProp)` that performs the **only**
string→type mapping:

- `"claude"`  → `(Claude, Posix, env)`
- `"windows"` → `(Claude, Windows, env)`   ← windows == claude-on-windows
- `"gemini"`  → `(Gemini, Posix, env)`
- default     → throw `IllegalArgumentException`

`gemini`+`windows` is simply never constructed — illegal state is unrepresentable.
Add convenience delegators (`cliBin(pluginName, version)`, `skillFragmentDir()`,
`launcherFileName()`) that forward to the axes.

Add `TargetTest` covering each of the five real variants and asserting the rejected
`gemini`/`windows` combination is unreachable through the public API (there is no path
that constructs it). This is the highest-risk task: it fixes the axis decomposition and
the legal-combination policy that everything else depends on.

### Task 5: Move the runtime-install command onto Os; collapse HooksRenderer branch [Medium]

*Depends-on: 4*

Add `Os.runtimeInstallCommands(String repoName, String version)` returning
`List<String>`. `Posix` returns `List.of()` (no install step). `Windows` returns the
`MSYS_NO_PATHCONV=1 cmd.exe /C "...install-runtime.bat"` command plus the
copy-src→dest commands, lifted from `HooksRenderer` and `PluginModel.windowsCacheRoot()`
/ `windowsRuntimeDest()`. Rewrite `HooksRenderer` to iterate
`model.target().os().runtimeInstallCommands(...)` with no `if (!isWindows())` branch —
the posix empty list makes the loop a no-op naturally.

This is the task that fixes the `.bat`/`.cmd` mismatch: the install path is now built
from the same `Os` that owns `launcherFileName()`. Extend
`ResourceBuilderIntegrationTest` to assert the Windows hooks.json is unchanged in
substance and that the launcher name is internally consistent.

### Task 6: Let packaging's PackageRuntime consume Os [Medium]

*Depends-on: 5*

`PackageRuntime` (in `packaging`, which already depends on `integration-common`)
replaces `boolean isWindows = target.startsWith("win32")` and the hardcoded
`bin/shipsmooth.cmd` / `java.exe` literals with calls into `Os` — resolving the `Os` from
its `target` argument (`win32*` → `Windows`, else `Posix`). The launcher *file name* and
java binary name now come from `Os.launcherFileName()` / `Os.javaExe()`, the single
source of truth shared with the skill renderer. The launcher *script bodies*
(`buildWindowsLauncher` / `buildLauncher`) stay in `PackageRuntime` for now — only the
varying tokens are sourced from `Os`.

Update `PackageRuntimeTest` to assert the launcher entry name equals
`Os.windows().launcherFileName()`, locking the two modules together.

### Task 7: Cut over PluginModel and delete BuildProfile [High]

*Depends-on: 5*

Replace `PluginModel`'s raw `String platform` with a `Target` field. Re-express
`cliBin`, `skillName`, `pluginName`, frontmatter wiring, and the
`withSkill(...)` copy through `Target`/`Env`. Delete `isWindows()`,
`windowsCacheRoot()`, `windowsRuntimeDest()` from `PluginModel` (now on `Os`). Update
`PluginModel.fromProperties` to call `Target.from(...)` instead of
`BuildProfile.fromProperties()`. Update `ResourceBuilder.fromProperties` and
`SkillRenderer` (which currently takes a `BuildProfile`) to use `Target`/`Env`. Delete
`BuildProfile.java` and `BuildProfileTest.java` once no references remain.

Run the full `ResourceBuilderIntegrationTest` and confirm rendered output for all five
variants is byte-identical to the pre-refactor baseline (capture the baseline first).
Highest-risk cutover task — touches every renderer's construction path.

### Task 8: Replace template platform branching with Platform.skillFragmentDir() [Medium]

*Depends-on: 7*

Replace the 9 `@if("gemini".equals(model.platform()))` / `@else` blocks in
`skills/_partials/parallel-execution.jte.md` and `base-workflow.jte.md` with a single
resolved include driven by `model.target().platform().skillFragmentDir()` (exposed to
the templates via a `PluginModel.skillFragmentDir()` delegator). The per-platform
fragment directories (`skills/start/claude/*`, `skills/start/gemini/*`) stay — only the
in-template string comparison is removed.

Re-run the integration test; rendered SKILL.md for claude and gemini variants must be
byte-identical to baseline. JTE templates are precompiled, so this task includes
verifying the jte recompilation still succeeds (`mvn compile` in `integration-common`).

## Open questions

- **Composition vs enumeration for `Target`.** Decided: composition
  (`record Target(Platform, Os, Env)`), on the expectation that the variant set is a
  growing constrained cross-product, with legal combinations enforced in
  `Target.from(...)`. The `sealed interface Target` with one impl per named variant
  (`ClaudePosix`, `ClaudeWindows`, `GeminiPosix`) alternative was considered and not
  chosen.
- **Where the `Os` type ultimately lives.** It stays in `integration-common` for now
  (packaging already depends on that module). A future extraction into a dedicated
  module is deliberately deferred until more shared surface accumulates.
