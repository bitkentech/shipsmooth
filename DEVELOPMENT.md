# Development

## Prerequisites
- Java 21
- Node.js 18+

(The build uses the Gradle wrapper, `./gradlew` — no separate Gradle install needed.)

## Repo structure

This repo uses a multi-module Gradle layout:
- `core/` — pure domain logic (workflow, ledger, git ops, plan service); JPMS module `io.bitken.ss.core`
- `cli/` — Java CLI (`shipsmooth`, picocli) + jlink image build; JPMS module `io.bitken.ss.cli`
- `skills/` — the skills product:
  - one folder per skill directly under `skills/` (`skills/start/`, `skills/experimental/refine/`, …), each with its `SKILL.jte.md`
  - `skills/shared/` — partials shared across skills (`shared/workflow/`) and the target snippets the shared workflow selects (`shared/workflow/claude/`, `shared/workflow/gemini/`)
  - `skills/pkg/` — Java renderers (`Target`, `SkillRenderer`, …) + TypeScript hook scripts (rarely touched)
- `claude/` — Claude plugin metadata (`claude-plugin/`, `windows/`)
- `gemini/` — Gemini extension metadata (`gemini-extension/`)
- `packaging/` — assembles the final `build/` output from the other modules
- `devtools/` — development-time helper scripts
- `exp/` — exploratory work with no build wiring (e.g. `exp/model/` TLA+ specs)

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
  skills/start/SKILL.md      (with YAML frontmatter; cliBin → runtime-<ver>/bin/shipsmooth)
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

Verifies the build layout (jlink runtime model: SKILL cliBin → `runtime-<ver>/bin/shipsmooth`, hook runs `session-start.js`, no shipped `package.json`) and links the extension.

### Uninstall

```bash
gemini extensions uninstall shipsmooth
```

### Notes
- `build-gemini-dev/` is gitignored — always a local, derived artifact
- Run `./gradlew assembleClaudeDev` to rebuild the Claude plugin; run `./gradlew assembleGeminiDev` for Gemini
- Each variant has its own explicit assemble task — there is no global default variant (Gradle dropped the Maven `activeByDefault` profile shortcut)

## Releasing a new version

### Prerequisites

- `gh` (GitHub CLI) installed and authenticated
- IBM Semeru JDK at `/opt/installers/jdk-semeru/jdk-25.0.2+10` (override with `-Djdk.semeru.linux-x64=<path>`)
- Working tree must be clean (no uncommitted changes on tracked files)

### Claude Code release

Releases are orchestrated by `PublishRelease.java` in `packaging`, wrapped as the Gradle
`publishRelease` task. It bumps the version, builds, packages the runtime zip, pushes to the
`releases` branch, and creates a GitHub Release — all in one command.

```bash
./gradlew publishRelease -Pshipsmooth.release.version=<version>

# Example:
./gradlew publishRelease -Pshipsmooth.release.version=0.3.16
```

`PublishRelease` performs these steps:
1. Asserts clean working tree and that tag `v<version>` does not exist
2. Bumps `plugin.version` in `gradle.properties` (the single version source) and commits
3. Builds the full plugin (`./gradlew assembleClaudeProd`) and the 5-platform jlink images
4. Packages the runtime zip (`shipsmooth-<version>-linux-x64.zip`)
5. Checks out the `releases` branch, replaces `dist/` with the new build output
6. Commits, tags `v<version>`, pushes `releases` branch and tag
7. Creates a GitHub Release and uploads the runtime zip as an asset
8. Returns to the original branch

Structure of the `releases` branch:
```
dist/
├── .claude-plugin/
├── hooks/hooks.json
├── dist/                  (compiled JS + session-start-config.json)
├── scripts/tasks/
└── skills/start/SKILL.md
```

The `releases` branch is an orphan — it shares no history with `main`.

### Gemini CLI release

Gemini CLI installs extensions by cloning a repo where `gemini-extension.json` lives at the root (see [Gemini CLI extension releasing docs](https://geminicli.com/docs/extensions/releasing/)). This is incompatible with the layout of the `releases` branch, where Claude's `.claude-plugin/` metadata sits at the root of `dist/`. Rather than add branch-switching complexity here, Gemini releases are published to a dedicated repo — [`bitkentech/shipsmooth-gemini`](https://github.com/bitkentech/shipsmooth-gemini) — whose `main` branch is a pure publish artifact fully replaced on each release.

```bash
./devtools/scripts/release-gemini.sh <version>
# Example:
./devtools/scripts/release-gemini.sh 0.0.1
```

The script:
1. Cleans `build-gemini/` and runs `./gradlew assembleGeminiProd -Pbuild.outputDir=build-gemini`
2. Stamps the version into `build-gemini/gemini-extension.json`
3. Clones `shipsmooth-gemini` into a temp directory
4. Replaces its contents with the new build output
5. Commits, tags `v<version>`, and pushes both branch and tag
6. Creates a GitHub Release in `shipsmooth-gemini` via `gh release create`
7. Cleans up the temp clone

Pass `--force` to skip the clean-tree check (useful during iterative testing):
```bash
./devtools/scripts/release-gemini.sh 0.0.2 --force
```

Structure of the `shipsmooth-gemini` repo after release:
```
├── gemini-extension.json   (version-stamped)
├── commands/start.toml
├── hooks/hooks.json        (SessionStart runs node "${extensionPath}/dist/session-start.js")
├── skills/start/SKILL.md
└── dist/                   (session-start.js + adm-zip-bundle.js + session-start-config.json)
```

### Windows release

The Windows plugin bundles a native jlink JRE so users need no Node.js or Java on PATH. It is published to [`bitkentech/shipsmooth-windows`](https://github.com/bitkentech/shipsmooth-windows) — a **deployment-only repo** where each release is an orphan commit force-pushed to `main`. No history is retained on the remote; only the latest release is installable. The full build history is recoverable from this repo.

**Prerequisite:** `shipsmooth-windows` must be cloned as a sibling of this repo:
```bash
# from the parent directory of shipsmooth:
git clone https://github.com/bitkentech/shipsmooth-windows
```

The default path is `../shipsmooth-windows` (relative to the repo root). Override with:
```
-Dshipsmooth.windows.repo=/path/to/shipsmooth-windows
```

**Full release (via PublishRelease):**

The single `publishRelease` task assembles all platforms — including the Windows plugin and its
bundled jlink JRE — and pushes to `shipsmooth-windows`. No separate Windows invocation is
needed.

```bash
./gradlew publishRelease -Pshipsmooth.release.version=<version>
```

`PublishRelease` performs these steps for Windows (as part of the same run):
1. Bumps `plugin.version` in `gradle.properties` and commits
2. Builds the jlink image (`./gradlew jlinkImage_windows-x64`) and `build-windows/` artifacts
3. Resolves the `shipsmooth-windows` sibling repo
4. Assembles `runtime/`, `hooks/`, `skills/`, and `.claude-plugin/` into that directory
5. Creates a fresh orphan commit (no prior history)
6. Force-pushes `main` to `origin` — replacing the previous single-commit release

**Windows-only fix release (manual orphan push):**

Use this when fixing a Windows-specific issue without bumping the main repo version.
The `-fixN` suffix is only a label in the release commit message — do **not** run `PublishRelease`
as it bumps `gradle.properties` and bakes the suffix into the build artifacts.

```bash
# Step 1: rebuild the jlink image (only needed if shipsmooth changed)
./gradlew jlinkImage_windows-x64 -PjlinkBuild

# Step 2: build the Windows plugin artifacts (plugin.version stays at e.g. 0.3.10)
./gradlew assembleWindows -Pbuild.outputDir=build-windows

# Step 3: wipe and re-populate the shipsmooth-windows working tree
MAIN_SHA=$(git rev-parse --short HEAD)
FIX_VERSION=0.3.10-fix6   # increment fixN each time
cd ../shipsmooth-windows
git checkout --orphan releases-$FIX_VERSION
git rm -rf --quiet .

# Step 4: copy build artifacts
# Note: cp -r with . skips hidden dirs — copy .claude-plugin separately
cp -r /path/to/shipsmooth/build-windows/. .
cp -r /path/to/shipsmooth/build-windows/.claude-plugin .
cp -r /path/to/shipsmooth/cli/build/jlink-image-windows-x64 runtime

# Step 5: commit and force-push
git add .
git commit -m "release: v$FIX_VERSION (main: $MAIN_SHA) — <brief description>"
git push origin releases-$FIX_VERSION:main --force
```

**Install and verify (on Windows):**
```
plugin install shipsmooth@bitkentech
```

Claude Code caches the plugin under `%USERPROFILE%\.claude\plugins\cache\bitkentech\shipsmooth\<version>\`. The `SessionStart` hook xcopy's the bundled JRE to `%LOCALAPPDATA%\shipsmooth\<version>\runtime\` on every session start.

Structure of the `shipsmooth-windows` repo after release:
```
├── .claude-plugin/
│   ├── plugin.json
│   └── marketplace.json
├── hooks/hooks.json
├── skills/start/SKILL.md
└── runtime/                (bundled Windows jlink JRE + shipsmooth.bat)
```
