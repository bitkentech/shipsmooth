# Development

## Prerequisites
- JDK 25 (the build runs on a Java 25 toolchain; bytecode targets Java 21)
- Node.js 18+

(The build uses the Gradle wrapper, `./gradlew`. No separate Gradle install needed.)

## Repo structure

This repo uses a multi-module Gradle layout:
- `core/`: pure domain logic (workflow, ledger, git ops, plan service); JPMS module `io.bitken.ss.core`
- `cli/` : Java CLI (`shipsmooth`, picocli) + jlink image build; JPMS module `io.bitken.ss.cli`
- `plugin-model/`: tiny leaf module of shared value types (`Os`, `Platform`, `Env`, `PluginModel`); no other module deps. `packaging` depends on it for `Os` alone.
- `skills/`: the skills product:
  - one folder per skill directly under `skills/` (`skills/start/`, `skills/experimental/refine/`, …), each with its `SKILL.jte.md`
  - `skills/shared/`: partials shared across skills (`shared/workflow/`) and the target snippets the shared workflow selects (`shared/workflow/claude/`, `shared/workflow/gemini/`)
  - `skills/pkg/`: the `:skills:pkg` module — `SkillRenderer` + JTE staging/generation only (renders the SKILL.md files; rarely touched). Depends on `plugin-model`.
- `harness/`: the per-host agent-harness plugin integrations + the shared renderer they drive. Add a new agent harness (e.g. opencode, pi) as `harness/<name>/`. (IDE/editor extensions, if added later, get their own top-level folder rather than living here.)
  - `harness/shared/` (`:harness:shared`): renders everything that is NOT the skill file — `Target` (orchestrator), `HooksRenderer`/`HookCommandRenderer` (hooks.json + the SessionStart command and its `install-shipsmooth.sh`/`install-runtime.bat` companion), `SessionStartConfigRenderer`, the TypeScript hook scripts (`scripts/`), `install-shipsmooth.sh`, and the `render*`/`copyDist*` tasks the host builds consume. Depends on `plugin-model` + `skills:pkg`.
  - `harness/claude/` (`:harness:claude`): Claude plugin metadata (`claude-plugin/`, `windows/`)
  - `harness/gemini/` (`:harness:gemini`): Gemini extension metadata (`gemini-extension/`)
  - `harness/codex/` (`:harness:codex`): Codex plugin metadata (`.codex-plugin/`)
- `packaging/`: assembles the final `build/` output from the other modules
- `devtools/` : development-time helper scripts
- `exp/` : exploratory work with no build wiring (e.g. `exp/model/` TLA+ specs)

## Build the dev version

```bash
./gradlew assembleClaudeDev
```

This produces a `build/` directory containing the `shipsmooth-dev` plugin and skill:
```
build/
  .claude-plugin/marketplace.json
  .claude-plugin/plugin.json
  hooks/hooks.json
  skills/start-dev/SKILL.md
  dist/                          (compiled JS + session-start-config.json)
  scripts/tasks/                 (compiled JS + TS source)
```

> `assembleClaudeDev` renders `SKILL.md`, `hooks/hooks.json`, and
> `dist/session-start-config.json` and composes the full payload into `build/`.

**Dev-loop shortcut:** `./gradlew :harness:claude:devBuild` assembles the same full
payload into repo-root `build/` *and* auto-builds the host jlink runtime image
(the dev `session-start-config.json` `jlinkDir` is wired to `:cli:image_<host>`,
so the image is built on demand — no `-PjlinkBuild` flag needed). Point Claude at
`build/` and you have a runnable dev plugin backed by a local runtime.

## Register the dev build with Claude Code

Add to `~/.claude/settings.json`:

```json
"extraKnownMarketplaces": {
  "shipsmooth-dev": {
    "source": {
      "source": "directory",
      "path": "/path/to/shipsmooth/build"
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
- `build/` is gitignored — it is always a local, derived artifact

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

## Releasing a new version

Release orchestration (Claude Code, Gemini CLI, and Windows releases, prod builds, and the
`publishRelease` task) lives in the `packaging` module. See
[`packaging/README.md`](packaging/README.md).
