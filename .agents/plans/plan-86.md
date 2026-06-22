# plan-86 — opencode plugin

## Context

Feature: add OpenCode (https://opencode.ai) as a fourth agent host for the
shipsmooth coding workflow, alongside the existing `claude`, `codex`, and
`gemini` harness integrations.

OpenCode differs structurally from the three existing hosts. The existing
integrations are *declarative resource bundles*: a `SKILL.md` (or prompt/command
file) plus a `SessionStart` hook described in JSON, both consumed by the host.
None of them ship executable plugin logic — the host runs the install script via
its own hook machinery.

OpenCode's primary extension surface is instead a **JavaScript/TypeScript plugin
module** that the host imports and runs. The decision for this plan (made at
kickoff) is to integrate through that JS/TS plugin system and its lifecycle
events, rather than relying on a passive SKILL.md drop. This makes OpenCode the
first host where shipsmooth ships *code*, not just rendered resources.

### What OpenCode gives us (the surfaces this design uses)

- **Plugin module.** A JS/TS file under `.opencode/plugin/` (project) or
  `~/.config/opencode/plugin/` (global), or an npm package named in
  `opencode.json`'s `plugin` array. It exports an `async` function receiving a
  context object `{ project, client, $, directory, worktree }` and returns a map
  of hook handlers. `$` is Bun's shell API; `client` is the OpenCode SDK client
  (logging, session, config). npm plugins and their deps are auto-installed with
  Bun at startup and cached under `~/.cache/opencode/node_modules/`.
- **Lifecycle events** usable as a SessionStart equivalent: `server.connected`
  (fires once when the server comes up) and `session.created`. These are where
  the runtime bootstrap fires — the analogue of the other hosts' `SessionStart`
  hook.
- **`config` hook.** Lets the plugin register slash commands programmatically
  (name → `{ template, description }`), so `/shipsmooth:start` can be contributed
  by the plugin itself rather than dropped as a loose command file.
- **Native skills.** OpenCode also reads `SKILL.md` skills (same format as
  Claude — `name`/`description` frontmatter, folder name must equal `name`) from
  `.opencode/skills/<name>/`, and as fallbacks from `.claude/skills/`,
  `~/.claude/skills/`, `.agents/skills/`. We still render and ship a `SKILL.md`
  so the workflow text is available on-demand via the native `skill` tool — but
  the JS plugin owns the bootstrap and the command entry point.

### The runtime-bootstrap problem (the core risk)

In `[Local]` mode the workflow calls a Java CLI (`shipsmooth …`) that lives in a
jlink runtime under `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/<version>/`. On the
other hosts a `SessionStart` hook runs `install-shipsmooth.sh <name> <version>`,
which downloads + unpacks that runtime on first use. OpenCode has no JSON
`SessionStart` hook — so the **JS plugin must drive the bootstrap itself** from a
lifecycle event. The existing `install-shipsmooth.sh` (Node-free POSIX installer,
already bundled as a classpath resource and reused by every host) is the asset we
shell out to; we do not reimplement download/unpack logic in JS.

### Backlog feature reference

This plan delivers the "OpenCode host support" feature. `[Local]` mode — recorded
here in the plan Context (no external backlog issue). Mirrors the precedent set by
the Codex host integration.

## Design

### Module shape — `harness:opencode`

Add a new Gradle module `harness:opencode`, registered in `settings.gradle.kts`
after `harness:codex`. It follows the same dependency direction as the other
harness modules: `plugin-model <- skills:pkg <- harness:shared <- harness:opencode`.
Like `claude`/`codex`/`gemini` it applies only the `base` plugin (resource
assembly + clean lifecycle, no Java toolchain in the module itself) — but it has
one extra concern the others lack: a **TypeScript plugin source** that must be
compiled/bundled to JS before packaging.

The assembled payload OpenCode consumes:

```
<payload-root>/                  # the plugin package root
  package.json                   # name, version, main, "@opencode-ai/plugin" dep
  dist/
    index.js                     # compiled plugin (from src TS)
  skills/
    start/SKILL.md               # rendered via Target (build.platform=opencode)
  hooks/
    install-shipsmooth.sh        # the bundled POSIX installer (emitted by the
                                 # shared renderer, same script the other hosts use)
```

Naming/versioning mirrors the other modules: prod name `shipsmooth`, dev name
`shipsmooth-dev`, version from `plugin.version`. Tokens (`plugin.name`,
`plugin.description`, `project.version`) are filtered into `package.json` exactly
as the codex/claude `registerXMeta` Copy+`expand()` tasks filter their manifests.

### New platform — `Platform.Opencode`

Extend the `Platform` sealed interface (currently `Claude | Gemini | Codex`) with
an `Opencode` record:

- `id()` → `"opencode"`
- `skillFragmentDir()` → `"start/opencode"` (new fragment dir; see below)

Add a `Platform.OPENCODE` constant and a `"opencode"` case to `Platform.from`.
Add `isOpencode()` to `PluginModel` for template branching, parallel to
`isCodex()`/`isGemini()`. OpenCode is POSIX-only (no Windows variant): the
existing `Target.guard` already rejects non-Claude on Windows, so no guard change
is needed beyond confirming opencode never builds a windows spec.

### Skill rendering — reuse the JTE pipeline unchanged

OpenCode's skill format equals Claude's, so the SKILL.md renders through the
existing `SkillRenderer` + JTE templates with **no template fork**. Add an
`opencodeProdSpec`/`opencodeDevSpec` `RenderSpec` in `harness:shared`'s build
(copied from the codex specs), differing only in:

- `buildPlatform = "opencode"`,
- `skillFrontmatter` — OpenCode requires `name` + `description` frontmatter on
  the skill (same as gemini/codex), so reuse the gemini frontmatter shape
  (`name: start` / `start-dev`).
- `pluginHookCommand` is **not used** for OpenCode (no JSON SessionStart hook).
  The render still emits `hooks/install-shipsmooth.sh` because the JS plugin
  invokes that script; but there is no `hooks.json` to write. This is the one
  place OpenCode diverges from the shared render contract — see "Open question
  A".

Per-platform skill body branches: `phase2-execute.jte.md` already switches on
`isCodex()/isGemini()/else`. OpenCode's set-commit lines are identical to the
others (they only interpolate `${model.cliBin()}`), so add an `isOpencode()`
branch that reuses the shared text — most simply by treating opencode like the
existing default rather than adding a third fragment dir, unless a genuine
divergence emerges.

### The JS/TS plugin (`src/index.ts`)

A single, small TypeScript module — the only executable code shipsmooth ships to
any host. Responsibilities, in order:

1. **Bootstrap the runtime on a lifecycle event.** On `server.connected` (and/or
   first `session.created`), shell out — via Bun's `$` from the plugin context —
   to the bundled installer:

   ```
   sh "<plugin-root>/hooks/install-shipsmooth.sh" shipsmooth <version>
   ```

   The version is read from a generated config file (see below), not hardcoded in
   TS, so a version bump re-renders one JSON file and the TS stays untouched. The
   installer is idempotent (it early-exits if the runtime is already present), so
   running it every server start is cheap. Bootstrap failures are logged via
   `client.app.log` and must be non-fatal — a missing runtime degrades to "CLI
   unavailable", it never blocks the session.

2. **Register the `start` command.** Via the `config` hook, contribute a
   `shipsmooth:start` (dev: `shipsmooth-dev:start`) command whose `template` is
   the workflow entry point — either inlining the rendered workflow or, preferably,
   pointing the agent at the native skill so the text stays single-sourced in the
   SKILL.md. (Command-vs-skill entry point is "Open question B".)

3. **Resolve the CLI binary.** A small bin-resolution helper returns the absolute
   path to the installed `shipsmooth` launcher
   (`${XDG_CACHE_HOME:-~/.cache}/shipsmooth/<version>/bin/shipsmooth`), with a
   `PATH` fallback. This is the same path shape `Os.Posix.cliBinPath` already
   encodes; the skill text itself already emits that path via `${model.cliBin()}`,
   so the plugin does not need to *run* CLI commands on the agent's behalf — the
   agent runs them from the skill. The resolver exists mainly so the bootstrap can
   verify success and log a clear message.

A generated `dist/opencode-config.json` (or reuse of the existing
`session-start-config.json` the shared `SessionStartConfigRenderer` already
writes) carries `{ name, version }` into the plugin at runtime — this is the
single source of the version the TS reads, keeping the compiled JS version-stamp-free.

### Build: TS compile + payload assembly

Two-stage, mirroring how the other modules assemble but adding a TS build:

- **Compile/bundle the TS.** A task that runs the TypeScript build (bundling
  `@opencode-ai/plugin` as external) to `build/dist/index.js`. Toolchain choice
  (tsc vs bun vs esbuild) is "Open question C"; the rest of the repo is
  Gradle-driven Java, so this is the one foreign toolchain the module introduces.
- **`assembleOpencodeDev` / `assembleOpencodeProd`.** Sync-based (sole-writer,
  overlap-immune) assembly that merges: the render output (`skills/` +
  `hooks/install-shipsmooth.sh` + the config JSON), the compiled `dist/index.js`,
  and the token-filtered `package.json`, into the payload root. Dev co-deposits
  into a gitignored `build-opencode-dev/`; prod targets `-Pbuild.outputDir`. This
  is the same dual-mode dev/prod split the codex module already uses.

### Packaging / release

The jlink runtime zips are platform-not-host artifacts and are already produced
by `:packaging`; OpenCode reuses the exact same release zips the other POSIX
hosts download (`shipsmooth-<version>-<os>-<arch>.zip`) via the shared installer.
So **no packaging change** is needed for the runtime. The only new release
artifact is the OpenCode plugin payload itself (publishable to npm, or installed
from the assembled directory) — wiring it into the release flow is a later task,
gated on the payload assembling correctly first.

## Decisions to resolve

The four design questions below are **decision tasks**, not implementation tasks.
Each must be resolved (a decision recorded in this plan) before the implementation
tasks that depend on it can be risk-calibrated and tasked out in Phase 1. They are
captured as tasks so the decision and its rationale land in the tracked task state
and the plan's git history.

### Task 1: Decide the render contract for the no-`hooks.json` host [Medium]

Claude/Codex/Gemini all emit a `hooks/hooks.json` declaring a `SessionStart` hook;
OpenCode consumes no such file. But OpenCode still needs `hooks/install-shipsmooth.sh`
(the JS plugin invokes it). Today `HooksRenderer.write()` produces both *together* —
the installer script is copied as a side effect of writing `hooks.json` — so the two
must be decoupled: ship the script without the JSON.

Decide between:
- **Branch in `Target.build()` on platform** — smallest diff; a `if (!isOpencode)`
  around `hooksRenderer.write()` plus an extracted "copy installer only" path.
- **Capability on `Platform`** (recommended) — e.g. `boolean emitsHooksJson()`;
  `Target` reads the capability instead of naming the platform. Keeps host-specific
  facts on `Platform` alongside `id()`/`skillFragmentDir()`; no special-casing to
  revisit for the next host.
- **Split `HooksRenderer`** into `InstallerScriptRenderer` (always) +
  `HooksJsonRenderer` (hook hosts only) — cleanest separation, largest refactor.

Either way, the shared sub-task is extracting "copy `install-shipsmooth.sh`" so it
runs independently of "write `hooks.json`". Record the chosen option and why.

### Task 2: Decide the entry point — plugin command vs native skill [Medium]

*Depends-on: 1*

OpenCode exposes the workflow two ways: a plugin-registered slash command
(`shipsmooth:start`, via the `config` hook — explicit, discoverable) and/or the
native `SKILL.md` skill (on-demand via the `skill` tool). Both can coexist, but the
workflow text must be single-sourced (the JTE-rendered SKILL.md), not duplicated
into a command template. Decide the canonical entry point and how the
command — if registered — points at the skill rather than inlining its text.
This shapes what the TS plugin's `config` hook does and whether the command
template is a thin pointer or empty.

### Task 3: Decide the TypeScript toolchain [Medium]

Pick the build tool for the single TS plugin module: tsc / bun / esbuild. Bun
aligns with OpenCode's own runtime and is the natural bundler; tsc is the
lowest-dependency option. Constraint: it must keep CI reproducible and not drag a
heavy new toolchain into the otherwise Gradle/Java build. Decide the tool, how it's
invoked from Gradle (Exec task wrapping the bundler), and how `@opencode-ai/plugin`
is treated (bundled-external). This gates the build/assembly implementation task.

### Task 4: Decide dev-loop ergonomics + target dir [Low]

The other hosts ship a `devBuild` convenience task assembling into repo-root
`build/`. Decide the OpenCode dev-payload target dir (e.g. `build-opencode-dev/`)
and how a developer points a local OpenCode at it — `.opencode/plugin/` symlink vs
an `opencode.json` `plugin` path entry — so the dev loop is documented and
repeatable. Lowest-risk; mostly convention.

## Implementation tasks (Phase 1 — after decisions above)

_To be risk-calibrated and ordered in Phase 1 once Tasks 1–4 are resolved.
Anticipated thin vertical slices, each depending on the relevant decision:_

- `Platform.Opencode` + `isOpencode()` + render spec wiring [Med] — *needs Task 1*
- Skill render parity for opencode [Low]
- The TS plugin module — runtime bootstrap + entry-point registration [High] —
  *needs Tasks 2, 3*
- TS build + `assembleOpencode{Dev,Prod}` [High] — *needs Tasks 3, 4*
- Release/distribution wiring for the plugin payload [Med]
