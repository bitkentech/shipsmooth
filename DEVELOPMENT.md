# Development

## Prerequisites
- JDK 25 (the build runs on a Java 25 toolchain; bytecode targets Java 21)
- Node.js 18+

The build uses the Gradle wrapper, `./gradlew`. No separate Gradle install needed.

## Repo structure

This repo uses a multi-module Gradle layout:
- `core`: pure domain logic (workflow, plans, tasks). JPMS module: `io.bitken.ss.core`
- `cli` : Java CLI (`shipsmooth`, picocli) + jlink image build. JPMS module: `io.bitken.ss.cli`
- `plugin-model`: tiny leaf module of shared value types (`Os`, `Platform`, `Env`, `PluginModel`)
- `skills`: 
  - one folder per skill directly under `skills/` (`skills/start/`, `skills/experimental/refine/`, …), each with its `SKILL.jte.md`
  - `skills/shared`: partials shared across skills (`shared/workflow/`) and the target snippets the shared workflow selects (`shared/workflow/claude/`, `shared/workflow/gemini/`)
  - `skills/pkg`: Processes the skill templates and generates the SKILL.md files.
- `harness`: Plugin code for various coding harnesses (Claude, Gemini etc).
  - `harness/shared` (`:harness:shared`): renders everything that is NOT the skill file — `Target` (orchestrator), `HooksRenderer`/`HookCommandRenderer` (hooks.json + the SessionStart command and its `install-shipsmooth.sh`/`install-runtime.bat` companion), `SessionStartConfigRenderer`, the TypeScript hook scripts (`scripts/`), `install-shipsmooth.sh`, and the `render*`/`copyDist*` tasks the host builds consume. Depends on `plugin-model` + `skills:pkg`.
- `packaging`: Code for generating the production build and packaging it
- `docker`: build tooling for the `bitkentech/shipsmooth-claude` sandbox image (`io.bitken.ss.docker`). No internal deps — consumes the *published* plugin, not the build graph. See [`docker/README.md`](docker/README.md).
- `devtools` : development time helper scripts
- `exp` : totally exploratory work. Not even included in builds.

## Build the dev version

```bash
./gradlew assembleClaudeDev
```

This produces a `build-claude-dev/` directory containing the `shipsmooth-dev` plugin and skill:
```
build-claude-dev/
  .claude-plugin/marketplace.json
  .claude-plugin/plugin.json
  hooks/hooks.json
  skills/start-dev/SKILL.md
  dist/                          (compiled JS + session-start-config.json)
  scripts/tasks/                 (compiled JS + TS source)
```

> `assembleClaudeDev` renders `SKILL.md`, `hooks/hooks.json`, and
> `dist/session-start-config.json` and composes the full payload into `build-claude-dev/`.

**Dev-loop shortcut:** `./gradlew :harness:claude:devBuild` assembles the same full
payload into repo-root `build-claude-dev/` *and* auto-builds the host jlink runtime image
(the dev `session-start-config.json` `jlinkDir` is wired to `:cli:image_<host>`,
so the image is built on demand — no `-PjlinkBuild` flag needed). Point Claude at
`build-claude-dev/` and you have a runnable dev plugin backed by a local runtime.

> **Dev and prod use separate output dirs.** The dev payload lands in `build-claude-dev/`;
> the prod assembly (`assembleClaudeProd`) targets `build/`. They are kept apart on purpose:
> the dev assembly co-deposits (it does not prune the destination), so a shared dir let a
> stale prod file leak into the dev payload. This mirrors `build-gemini-dev/` /
> `build-codex-dev/`.

## Register the dev build with Claude Code

Add to `~/.claude/settings.json`:

```json
"extraKnownMarketplaces": {
  "shipsmooth-dev": {
    "source": {
      "source": "directory",
      "path": "/path/to/shipsmooth/build-claude-dev"
    }
  }
},
"enabledPlugins": {
  "shipsmooth@bitkentech": false,
  "shipsmooth-dev@shipsmooth-dev": true
}
```

Replace `/path/to/shipsmooth` with the absolute path to this repo.

## Usage

Start Claude in any project. The `/shipsmooth-dev:start-dev` slash command invokes the dev build.

## Switching back to production

Toggle `enabledPlugins` in `~/.claude/settings.json`:

```json
"enabledPlugins": {
  "shipsmooth@bitkentech": true,
  "shipsmooth-dev@shipsmooth-dev": false
}
```

## Notes
- Restart Claude after each `./gradlew assembleClaudeDev` run to pick up changes
- `build-claude-dev/` is gitignored — it is always a local, derived artifact
- The Claude **prod** SessionStart hook command (in `claudeSpec`, `harness/shared/build.gradle.kts`)
  must use `${CLAUDE_PLUGIN_ROOT:-<fallback>}`, never a bare `${CLAUDE_PLUGIN_ROOT}`.
  Claude Code leaves that variable **empty for SessionStart hooks**
  ([anthropics/claude-code#27145](https://github.com/anthropics/claude-code/issues/27145)),
  so a bare reference expands to `/hooks/install-shipsmooth.sh` in the cloud/remote env
  and installs nothing. The `verifyClaudeHookFallback` check task fails the build if this
  regresses.

## Gemini CLI development

### Prerequisites
- Gemini CLI installed (`npm install -g @google/gemini-cli`)

### Build the Gemini extension

```bash
./gradlew assembleGeminiDev
```

This produces `build-gemini-dev/` containing the Gemini extension:
```
build-gemini-dev/
  gemini-extension.json
  skills/start/SKILL.md      (with YAML frontmatter; cliBin -> <version>/bin/shipsmooth)
  hooks/hooks.json           (SessionStart runs node "${extensionPath}/dist/session-start.js")
  commands/start.toml
  dist/                      (session-start.js + adm-zip-bundle.js + session-start-config.json)
```

### Link for local development

```bash
gemini extensions link --consent build-gemini-dev/
```

Changes to source files are reflected immediately after the next `./gradlew assembleGeminiDev` run — no re-link needed (Gemini reads from the source path at load time).

### Run smoke tests

```bash
./devtools/scripts/smoke-gemini.sh
```

Verifies the build layout (jlink runtime model: SKILL cliBin -> `<version>/bin/shipsmooth`, hook runs `session-start.js`, no shipped `package.json`) and links the extension.

### Uninstall

```bash
gemini extensions uninstall shipsmooth
```

### Notes
- `build-gemini-dev/` is gitignored — always a local, derived artifact
- Run `./gradlew assembleClaudeDev` to rebuild the Claude plugin, `assembleGeminiDev` for Gemini, `assembleCodexDev` for Codex
- Each variant has its own explicit assemble task — there is no global default variant (Gradle dropped the Maven `activeByDefault` profile shortcut)

## Codex CLI development

### Prerequisites
- Codex CLI installed (with `codex plugin` support)

### Build the Codex plugin

```bash
./gradlew assembleCodexDev
```

This produces `build-codex-dev/` — a Codex *marketplace root* (not a flat plugin
payload). Codex nests the plugin under `plugins/<name>/` and keeps the marketplace
registration one level up under `.agents/plugins/`:
```
build-codex-dev/
  .agents/plugins/marketplace.json        (name/interface/plugins[] schema)
  plugins/shipsmooth-dev/                  (the plugin; source.path = ./plugins/shipsmooth-dev)
    .codex-plugin/plugin.json
    skills/start/SKILL.md                  (folder name matches the skill's frontmatter `name`)
    hooks/hooks.json                       (SessionStart runs node "${PLUGIN_ROOT}/dist/session-start.js")
    dist/                                  (session-start.js + adm-zip-bundle.js + session-start-config.json)
```

> Do **not** pass `-Pbuild.outputDir` to `assembleCodexDev` — the codex render
> writes to its own default render dir, and the nested `plugins/<name>/` layout is
> composed by the assemble Sync. Overriding the output dir breaks the render target.

### Register the dev build with Codex

Codex installs from a marketplace via the CLI (not a `cp -R`):

```bash
codex plugin marketplace add build-codex-dev/        # register the local marketplace root
codex plugin list                                    # shows the plugin
codex plugin add shipsmooth-dev@shipsmooth-dev       # <plugin>@<marketplace>
```

Codex caches the plugin to `~/.codex/plugins/cache/<marketplace>/<plugin>/<version>/`.
After a re-render (`./gradlew assembleCodexDev`), re-run `codex plugin add …` to
refresh the cache — a local marketplace is read live from disk.

### Notes
- `build-codex-dev/` is gitignored — always a local, derived artifact (distinct from
  `build-codex/`, which holds the plan-77 hand-built de-risk artifact)
- The SessionStart hook bootstraps the jlink runtime per session (reusing
  `install-shipsmooth.sh`), exactly like Claude/Gemini — no one-time installer
- There is no Codex smoke script yet (Claude/Gemini have one; Codex does not)

## OpenCode development

### Prerequisites
- OpenCode CLI installed (`opencode --version`; verified on 1.17.9)
- System Node + npm on PATH (the `harness:opencode` module builds the TS plugin
  and packs the npm tarball with them; Bun is not required for the build)

### Build the OpenCode plugin

```bash
./gradlew :harness:opencode:assembleOpencodeDev
# or the uniformly-named alias:
./gradlew :harness:opencode:devBuild
```

This produces `build-opencode-dev/` in OpenCode's config-dir layout:

```
build-opencode-dev/
  package.json                       # name: shipsmooth-dev (filesystem-only; never published)
  skills/start-dev/SKILL.md          # discovered at <config-dir>/skills/<name>
  plugin/
    index.js                         # the plugin entry OpenCode loads
    lib/internal.js                  # helpers — under plugin/lib/ ON PURPOSE (see Notes)
    dist/session-start-config.json   # {name, version} the plugin reads at runtime
    hooks/install-shipsmooth.sh      # bootstraps the jlink runtime
```

### Run the dev plugin

OpenCode has no `--plugin-dir` flag and its `opencode.json` `plugin` array accepts
**npm package names only**. The dev loop instead uses `OPENCODE_CONFIG_DIR`, which
points OpenCode at a self-contained config dir (it *replaces* the global config):

```bash
OPENCODE_CONFIG_DIR=$(pwd)/build-opencode-dev opencode
```

OpenCode loads `plugin/index.js`, registers the `/shipsmooth-dev:start` command, and
reads the `start-dev` skill. After a re-render, just restart OpenCode.

### Notes
- `build-opencode-dev/` (and prod `build-opencode/`) are gitignored — local derived
  artifacts. Re-assemble against a clean dir: OpenCode runs an install inside any
  config dir holding a `package.json`, dropping a `node_modules/` that the sole-writer
  Sync would otherwise collide with.
- **Helpers live under `plugin/lib/`, not beside `index.js`.** OpenCode scans
  `<config-dir>/plugin/*.js` **non-recursively** and treats every export of each
  file as a plugin factory. The entry (`index.js`) therefore exports only the
  factory; the pure helpers sit in `plugin/lib/internal.js` so the scan never loads
  them (a sibling `plugin/internal.js` would be loaded and rejected).
- Do **not** pass `-Pbuild.outputDir` to `assembleOpencodeDev` — the dev render reads
  from its own fixed stage; the property is for `assembleOpencodeProd` only.
- The plugin bootstraps the jlink runtime on `session.created` (reusing
  `install-shipsmooth.sh`); there is **no** `hooks/hooks.json` (OpenCode reads none).

### Distribution (prod)

The prod payload is published to npm as **`@bitkentech/shipsmooth-opencode`**; users
install it via `opencode.json`:

```json
{ "$schema": "https://opencode.ai/config.json", "plugin": ["@bitkentech/shipsmooth-opencode"] }
```

The jlink runtime is unchanged — OpenCode reuses the shared POSIX release zips via
the bundled installer (no packaging change). Build + pack the tarball locally:

```bash
./gradlew :harness:opencode:assembleOpencodeProd -Pbuild.outputDir=$(pwd)/build-opencode
./gradlew :harness:opencode:npmPackOpencode      -Pbuild.outputDir=$(pwd)/build-opencode
# -> harness/opencode/build/npm/bitkentech-shipsmooth-opencode-<ver>.tgz
```

Publishing is a **separate, standalone step** — the dedicated `publishReleaseOpenCode`
Gradle task, run by a human with npm auth:

```bash
./gradlew publishReleaseOpenCode -Pshipsmooth.release.version=<X.Y.Z>
# assembles + validates build-opencode/, then npm-publishes @bitkentech/shipsmooth-opencode
```

It is **decoupled from the main `publishRelease`** on purpose: npm publish needs an npm
session/token authorised for the `@bitkentech` scope (a one-time `npm login` / a
`NODE_AUTH_TOKEN` / `.npmrc` — the npm analogue of `gh auth`), and that credential isn't
reliably present at release time. The main release therefore **skips opencode-npm by
default**, so a missing token can never strand the GitHub/Windows releases. A fully-authed
operator can still do it in one shot with `./gradlew publishRelease -PpublishOpencodeNpm`
(that step runs last, after the Windows release). Re-running is safe — an already-published
version is an idempotent no-op. Only the prod variant ships to npm; the dev variant is
filesystem-only (`OPENCODE_CONFIG_DIR`).

## Releasing a new version

Release orchestration (Claude Code, Gemini CLI, and Windows releases, prod builds, and the
`publishRelease` task) lives in the `packaging` module. See
[`packaging/README.md`](packaging/README.md).

## Docker image (Claude Code sandbox)

The `bitkentech/shipsmooth-claude` image (Ubuntu + Node + Claude Code + the pre-installed
plugin) is built by the `docker` module. It is **not** wired into `publishRelease` — it is
a downstream consumer of the *published* plugin, built and pushed deliberately with
`./gradlew :docker:buildAndPush`. The shipsmooth version baked in comes from the root
`plugin.version`; Claude Code is resolved from the npm `stable` dist-tag at build time.

Maintainer reference (tasks, credentials, the three version channels):
[`docker/README.md`](docker/README.md). End-user usage: [`DOCKER.md`](DOCKER.md).
