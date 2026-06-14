# Releasing a new version

This `packaging` module assembles the final build output and orchestrates releases.
For general development setup, the repo structure, and the dev build loop, see
[`../DEVELOPMENT.md`](../DEVELOPMENT.md).

## Prerequisites

- `gh` (GitHub CLI) installed and authenticated
- IBM Semeru JDK at /opt/installers/jdk-semeru/jdk-25.0.2+10 (override with `-Djdk.semeru.linux-x64=<path>`)
- Working tree must be clean (no uncommitted changes on tracked files)

## How the shipsmooth CLI is distributed

The shipsmooth CLI is not shipped as source or a bare jar. The `cli` module's jlink
build produces a self-contained [jlink image](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jlink.html) (the CLI classes plus a
trimmed JDK runtime), which is published as an asset on GitHub Releases. When a user
installs the plugin, the image for their platform is downloaded from the release and
unpacked locally.

Because the jlink image bundles its own Java runtime, the user does not have to
install Java separately to run shipsmooth. The trade-off is size: each platform's
image is roughly 80–95 MB unpacked on disk. It is downloaded as a compressed 
archive of around 45–50 MB, which is why it lives in Release assets rather than in
the repo.

## Full release (via PublishRelease)

The single `publishRelease` task assembles all platforms (Claude Code, Gemini CLI, and
the Windows plugin (including its bundled jlink JRE)). It builds and publishes all of them
in one command. No separate per-platform invocation is needed.

```bash
./gradlew publishRelease -Pshipsmooth.release.version=<version>
```

The per-platform details like what `PublishRelease` does for each target, what is the resulting repo/branch layout, are described in the sections below.

## Claude Code release

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
3. Builds the full plugin (`./gradlew assembleClaudeProd`) and the 4-platform jlink images
   with `-Pbuild.env=prod` (see *Prod builds* below) — **you do not pass any flag**; the
   release path supplies it
4. **Runs the release guard:** verifies the `EXPERIMENTAL_BUILD`/`VERSION` constants baked
   into every shipped image and execs the linux-x64 launcher's `--version`/`--help`. The
   release aborts here if a binary is stale or leaks the experimental surface
5. Packages the runtime zip (`shipsmooth-<version>-linux-x64.zip`)
6. Checks out the `releases` branch, replaces `dist/` with the new build output
7. Commits, tags `v<version>`, pushes `releases` branch and tag
8. Creates a GitHub Release and uploads the runtime zip as an asset
9. Returns to the original branch

### Prod builds (`-Pbuild.env=prod`)

`build.env` is the single prod/dev signal. A prod build bakes `EXPERIMENTAL_BUILD=false`
(hiding `--enable-experimental` and the experimental subcommands from `--help`) **and**
writes each jlink image to a `-prod` folder (`cli/build/jlink-image-<platform>-prod`) that
the release reads exclusively — so a release can never reuse a stale dev image. `publishRelease`
passes `-Pbuild.env=prod` for you; a plain `./gradlew` build (no `build.env`) is a dev build.
Any *manual* image/assemble invocation intended for release must pass `-Pbuild.env=prod`.

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

## Gemini CLI release

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

## Windows release

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

As part of a [full release](#full-release-via-publishrelease), `PublishRelease` performs
these steps for Windows:
1. Bumps `plugin.version` in `gradle.properties` and commits
2. Builds the jlink image (`./gradlew :cli:image_windows-x64`) and `build-windows/` artifacts
3. Resolves the `shipsmooth-windows` sibling repo
4. Assembles `runtime/`, `hooks/`, `skills/`, and `.claude-plugin/` into that directory
5. Creates a fresh orphan commit (no prior history)
6. Force-pushes `main` to `origin` — replacing the previous single-commit release

**Windows-only fix release (manual orphan push):**

Use this when fixing a Windows-specific issue without bumping the main repo version.
The `-fixN` suffix is only a label in the release commit message — do **not** run `PublishRelease`
as it bumps `gradle.properties` and bakes the suffix into the build artifacts.

```bash
# Step 1: rebuild the jlink image as PROD (only needed if shipsmooth changed).
# -Pbuild.env=prod bakes EXPERIMENTAL_BUILD=false and writes to the -prod folder;
# omitting it would ship a dev binary that leaks the experimental surface.
./gradlew :cli:image_windows-x64 -Pbuild.env=prod

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
# Read the PROD image folder (-prod), not the dev one:
cp -r /path/to/shipsmooth/cli/build/jlink-image-windows-x64-prod runtime

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
