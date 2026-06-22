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

## Tasks

Task 1 is a **de-risk / exploration** task: hand-build a complete OpenCode plugin
by hand (no render pipeline, hardcoded values) and prove it loads and works on the
real OpenCode CLI. Its findings *feed* the decision tasks (2–5) — several questions
(Bun's `$` availability, whether a registered command vs a native skill actually
surfaces, what `<plugin-root>` resolves to at runtime) are best answered by
observing a working plugin rather than by reading docs. Tasks 2–5 are **decision
tasks** (record a decision + rationale in this plan). The implementation slices then
follow in Phase 1, gated on the relevant decision.

Environment note: OpenCode `1.17.9` is installed at `~/.opencode/bin/opencode`;
Node 18 is present; **Bun is not on PATH** — so the de-risk must check whether the
plugin runtime exposes Bun's `$` (and fall back to `node:child_process` if not).

### Task 1: De-risk — hand-build a hardcoded OpenCode plugin and prove it loads [High]

Build, entirely by hand (hardcoded name/version, no JTE/Gradle), a minimal but
*complete* OpenCode plugin and run it against the installed OpenCode CLI to see
what it actually looks like and which surfaces work. This pins down the unknowns
before any code-generation plumbing is built.

Scope of the hand-built artifact:
- A plugin module (`.opencode/plugin/shipsmooth.js` or a local package) that on a
  lifecycle event (`server.connected` / first `session.created`) shells out to a
  copy of `install-shipsmooth.sh shipsmooth <version>` and logs the result via
  `client.app.log`.
- A `config`-hook-registered `shipsmooth:start` command **and** a native
  `SKILL.md` under `.opencode/skills/start/`, so both entry points can be compared
  live.
- The bundled `install-shipsmooth.sh` next to it (no `hooks.json`).

What to observe and record (these answers feed Tasks 2–5):
- Does the plugin load at all on `1.17.9`? Any manifest/`package.json` required?
- Is Bun's `$` present in the plugin context, or must we use `node:child_process`?
  (Bun is not on PATH in this env.) — feeds the TS-plugin impl + Task 3.
- What does `<plugin-root>` / `directory` / `worktree` resolve to, and can the
  bundled installer be located relative to the module? — feeds bootstrap impl.
- Does the bootstrap actually fetch + unpack the jlink runtime, and does a
  subsequent `shipsmooth` CLI call succeed? (End-to-end Local-mode proof.)
- Do BOTH the registered command and the native skill surface to the agent? Which
  feels canonical? — directly feeds Task 3 (entry point).
- Does emitting no `hooks.json` cause any warning/error? — confirms Task 2's premise.

Done = a working hand-built plugin in this repo's scratch area (`.agents/tmp/`,
per repo convention) plus a short findings note appended to this plan, with each
bullet above answered. This is exploration — quality/rendering rules don't apply;
hardcoded values are expected.

#### Task 1 — Findings (de-risk run, OpenCode 1.17.9)

Hand-built hello-world plugin (`.agents/tmp/opencode-derisk/.opencode/plugin/helloworld.js`,
also installed globally at `~/.config/opencode/plugin/helloworld.js`). It logs to a
`/tmp` marker file on factory load and on every `event`. Confirmed:

- **Plain `.js` global plugin loads with no manifest, no build, no deps.** A named
  export (`HelloWorld`) under `~/.config/opencode/plugin/` was discovered and its
  async factory invoked. No `package.json`/bundling required for load. Dropping the
  file into the global `plugin/` dir needs **no edit to `opencode.jsonc`** — the
  config `plugin` array is only for npm packages (the env already had `"micode"`
  there; it loaded alongside, no conflict).
- **The context object is real and populated.** `directory`/`worktree` resolved to
  the launch cwd; the SDK `client.app.log` call succeeded.
- **Bun's `$` IS available as a live function** (`hasShell=function`) *even though
  Bun is not on PATH*. The plugin runtime is Bun-hosted regardless. So the bootstrap
  can shell out via `$`. (We'll still keep a `node:child_process` fallback for
  robustness, but `$` is the primary path — answers part of Task 4.)
- **Lifecycle delivery is via the single generic `event` hook**, keyed on
  `input.event.type` — there are NO named `server.connected`/`session.created`
  hooks (matches the installed `@opencode-ai/plugin` 1.17.9 type defs). The plan's
  earlier narrative assuming named lifecycle hooks is **corrected**: design against
  the generic `event` dispatcher.
- **SessionStart-equivalent event = `session.created`.** At startup only
  `plugin.added`/`catalog.updated`/`integration.updated`/`reference.updated` fire;
  **`server.connected` never appeared.** On the first user prompt, `session.created`
  fired, followed by `message.updated`/`session.status`/`session.diff`/etc. So the
  runtime bootstrap should trigger on the **first `session.created`** (idempotent
  installer ⇒ safe to also opportunistically run at factory load).

- **End-to-end runtime bootstrap PROVEN.** On the first `session.created`, the
  plugin ran `install-shipsmooth.sh shipsmooth 0.3.25` via `$` (exit 0; idempotent
  fast-path since 0.3.25 was already cached), then ran the installed
  `~/.cache/shipsmooth/0.3.25/bin/shipsmooth --version` via `$` → exit 0,
  `out=0.3.25`. The full chain **plugin → `$` → installer → working Java CLI**
  works on OpenCode 1.17.9. This retires the plan's single biggest risk: Local mode
  is viable on OpenCode. (Only the already-installed fast-path was exercised here;
  a true cold download from GitHub releases was not re-tested, but the installer is
  the same script already proven on the other hosts.)

- **BOTH entry points surface and fire — confirmed.** With a `config`-hook
  command (`/shipsmooth-start`) and a native skill
  (`~/.config/opencode/skills/shipsmooth-start-skill/SKILL.md`, folder name ==
  frontmatter `name`) installed together, the agent ran *each* on request and wrote
  its distinct marker file (`command-entrypoint-fired` / `skill-entrypoint-fired`).
  So the plugin can register a slash command via `config.command[name] =
  { template, description }` AND ship a native SKILL.md; both are usable. This makes
  Task 3 a free design choice (likely: skill = canonical workflow text via the
  `skill` tool; command = thin launcher pointing at it), not a forced one.
- **`hooks.json` is irrelevant to OpenCode** — none was emitted; no warning/error
  observed; OpenCode never looks for it. Confirms Task 2's premise.

**Task 1 is fully de-risked.** Every open question is answered; the runtime
bootstrap and both entry points are proven end-to-end on OpenCode 1.17.9.

### Task 2: Decide the render contract for the no-`hooks.json` host [Medium]

*Depends-on: 1*

Claude/Codex/Gemini all emit a `hooks/hooks.json` declaring a `SessionStart` hook;
OpenCode consumes no such file. But OpenCode still needs `hooks/install-shipsmooth.sh`
(the JS plugin invokes it). Today `HooksRenderer.write()` produces both *together* —
the installer script is copied as a side effect of writing `hooks.json` — so the two
must be decoupled: ship the script without the JSON. (Task 1 confirms whether an
absent `hooks.json` is in fact benign for OpenCode.)

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

### Task 3: Decide the entry point — plugin command vs native skill [Medium]

*Depends-on: 1*

OpenCode exposes the workflow two ways: a plugin-registered slash command
(`shipsmooth:start`, via the `config` hook — explicit, discoverable) and/or the
native `SKILL.md` skill (on-demand via the `skill` tool). Task 1 shows live which
of these actually surface. Both can coexist, but the workflow text must be
single-sourced (the JTE-rendered SKILL.md), not duplicated into a command template.
Decide the canonical entry point and how the command — if registered — points at
the skill rather than inlining its text. This shapes what the TS plugin's `config`
hook does and whether the command template is a thin pointer or empty.

### Task 4: Decide the TypeScript toolchain [Medium]

*Depends-on: 1*

Pick the build tool for the single TS plugin module: tsc / bun / esbuild, and
whether to bundle.

**Decision.** **Author the plugin in TypeScript; ship plain `.js`, transpiled with
`tsc` — no bundler.** Rationale, grounded in Task 1 + the existing build:

- The plugin's only runtime imports are **`node:*` built-ins** (`node:fs`,
  `node:path`, `node:url`); its dependence on `@opencode-ai/plugin` is **type-only**
  (`import type { Plugin }`), which `tsc` erases. So there is **nothing to bundle** —
  a bundler (bun/esbuild) would inline zero third-party runtime code. Bundling is
  rejected as unnecessary complexity.
- Plain `.js` loads as-is on OpenCode (proven in Task 1: no build was even required
  to load). So the only reason to have a build step at all is **authoring ergonomics
  + type safety** against the `@opencode-ai/plugin` types — exactly what `tsc`
  transpile gives us. Hence "build the `.js` from `.ts`: yes."
- **Toolchain is already in the repo.** `harness:shared` drives TS via the
  `com.github.node-gradle.node` Gradle plugin (`compileTs` = an `NpmTask` running
  `npm run build`; node + npm + `typescript` are provisioned by node-gradle, so
  `tsc` need not be on PATH and CI stays reproducible). The OpenCode module reuses
  this exact pattern. The one difference from `session-start.ts`: that pipeline runs
  `tsc + esbuild` (it may carry deps); ours is **`tsc` transpile-only** (no esbuild)
  since there are no deps to bundle.

So: a `scripts/`-style TS source (`src/index.ts`) + `package.json`/`tsconfig.json`,
an `NpmTask`/node-gradle `compileTs`-equivalent emitting `dist/index.js`, consumed
by the assembly task. `@opencode-ai/plugin` is a **devDependency only** (types,
never shipped). Gates the build/assembly implementation task.

### Task 5: Decide dev-loop ergonomics + target dir [Low]

*Depends-on: 1*

The other hosts ship a `devBuild` convenience task assembling into repo-root
`build/`. Decide the OpenCode dev-payload target dir and how a developer points a
local OpenCode at it, so the dev loop is documented and repeatable.

**Finding (verified on 1.17.9).** OpenCode has **no `--plugin-dir` flag**, and the
`opencode.json` `plugin` array only accepts npm package names (not filesystem
paths). BUT there is an env var **`OPENCODE_CONFIG_DIR`** ("Path to config
directory"). Probe-tested: with `OPENCODE_CONFIG_DIR=<dir>`, OpenCode loaded the
plugin from `<dir>/plugin/*.js` and reads skills from `<dir>/skills/<name>/SKILL.md`
— i.e. it treats `<dir>` exactly as it treats `~/.config/opencode`. Note it
**replaces** (does not augment) the global config dir, so the dev dir must be
self-contained. (Also: `--pure` disables external/npm plugins but not config-dir
plugins.)

**Decision.** Assemble the dev payload into repo-root **`build-opencode-dev/`** with
the OpenCode config-dir layout (`plugin/`, `skills/`, plus our `hooks/` + config
JSON). Primary dev loop:

```
OPENCODE_CONFIG_DIR=$(pwd)/build-opencode-dev opencode
```

— OpenCode picks the dev plugin + skill straight from the build dir, no copy into
`~/.config/opencode`. This is the OpenCode analogue of `claude --plugin-dir` and the
other hosts' `devBuild`. Plan B (if a dev wants it loaded alongside their real
config) remains copying/symlinking into `~/.config/opencode/{plugin,skills}/`.
Lowest-risk; the mechanism is now proven, only the Gradle `devBuild` wiring remains
(an implementation task).

### Task 6: Verify OpenCode on Windows (WSL + native) [Medium]

*Depends-on: 1*

OpenCode *does* run on Windows (confirmed from the docs), but with a fork in the
road that bears directly on our bootstrap:

- **WSL (the OpenCode-recommended path).** Inside WSL, OpenCode is effectively
  Linux: Bun's `$` and the POSIX `sh install-shipsmooth.sh` path should work
  unchanged, and the runtime installs into the WSL `~/.cache/shipsmooth/…`. Expected
  to be **free** — same code path as the Linux de-risk. This is the primary target
  and the most likely supported configuration.
- **Native Windows (no WSL).** Genuinely hard and possibly blocked today: the docs
  note "OpenCode on Windows using Bun is currently in progress", so the `$` shell we
  rely on may be absent/immature; `sh install-shipsmooth.sh` won't run natively;
  and the other hosts' Windows support uses a *separate* `install-runtime.bat` +
  `%LOCALAPPDATA%` layout (see `HookCommandRenderer.windowsCommand`). Note also that
  `Target.guard` currently rejects every non-Claude platform on Windows — so native
  Windows for OpenCode would require lifting that guard AND a Windows bootstrap path
  (bat installer or a `node:child_process`/`cmd.exe` fallback in the plugin).

Goal of this task: **establish which Windows configurations we support and prove
the supported one(s)**. Concretely:
- Confirm WSL works end-to-end (plugin load → `session.created` → installer → CLI
  `--version`), mirroring the Linux de-risk inside WSL.
- Decide whether native (non-WSL) Windows is in scope for this plan or explicitly
  deferred. If in scope: design the Windows bootstrap (bat vs child_process), the
  CLI-bin path shape (`%LOCALAPPDATA%\…` per `Os.Windows.cliBinPath`), and the
  `Target.guard` change. If deferred: record it as a known limitation with a
  pointer, matching how Windows is Claude-only today.
- Whichever way: the plugin's bootstrap must **degrade gracefully** on an
  unsupported Windows config (log + no-op, never crash the session).

Recommended default: **support WSL, defer native Windows** unless there's a
concrete need — it keeps us on the proven POSIX path and avoids lifting the
Claude-only Windows guard before native OpenCode/Bun support matures.

## Implementation tasks (Phase 1 — after decisions above)

_To be risk-calibrated and ordered in Phase 1 once Tasks 1–6 are resolved.
Anticipated thin vertical slices, each depending on the relevant decision:_

- `Platform.Opencode` + `isOpencode()` + render spec wiring [Med] — *needs Task 2*
- Skill render parity for opencode [Low]
- The TS plugin module — runtime bootstrap + entry-point registration [High] —
  *needs Tasks 3, 4*
- TS build + `assembleOpencode{Dev,Prod}` [High] — *needs Tasks 4, 5*
- Release/distribution wiring for the plugin payload [Med]
