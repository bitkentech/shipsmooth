# Plan 42 — Java-based release pipeline (Linux x64, v0.3.0)

## Status: Complete

## Backlog issue
<!-- No Linear — tracked locally -->
Feature: replace shell-script release process with Java code; ship first Java-runtime release (v0.3.0).

## Context

### Current release flow (v0.2.0 — broken)
- `release.sh` runs `mvn compile -Pprod`, copies output to `releases` orphan branch, tags `v0.2.0`, creates GitHub Release.
- **No runtime zip was ever built or uploaded.** v0.2.0 on GitHub Releases has zero assets.
- `session-start.js` tries to download `shipsmooth-tasks-{version}-linux-x64.zip` from the GitHub Release → would 404 on any fresh install.
- `package-tasks-java.sh` exists but is a manually-run shell script, never wired into the release flow.

### Java runtime (already working)
- `plugin-tasks-java` builds a jlink image (IBM Semeru OpenJ9 25) via `mvn -pl plugin-tasks-java -am -Pjlink package`.
- Output: `plugin-tasks-java/target/jlink-image/` — ~85 MB, contains `bin/shipsmooth-tasks` launcher.
- `runtime-0.2.0/` in the repo root is an example of what a shipped runtime looks like.

### plugin-dist (target module)
- Switching from `<packaging>pom</packaging>` to `<packaging>jar</packaging>` — tested, existing resource-copy executions are unaffected.
- No Java source yet.
- Orchestrates resource copying via `maven-resources-plugin` executions.
- Depends on `plugin-node`, `plugin-resources`, `plugin-skill`.

### session-start.ts
- Already has `detectPlatform()` returning e.g. `linux-x64`, `darwin-x64`, `darwin-arm64`.
- Hard-errors on anything other than `linux-x64` (line 24) — acceptable for v0.3.0.
- Download URL is hardcoded to `linux-x64` (line 57) — needs to use `detectPlatform()` result, but since we only ship linux-x64 for now, this is fine to leave as-is.

## Goal

Replace the release process with Java code in `plugin-dist`. A single `mvn` invocation on Linux produces and publishes a complete v0.3.0 release including the runtime zip as a GitHub Release asset. No `install` step required — `compile` + `exec:java` is sufficient.

## Design decisions

### Plain Java + exec-maven-plugin, not a Maven Mojo
A Maven plugin cannot execute itself in the same reactor build without an install step. Since we want `mvn -pl plugin-dist compile exec:java` to work, we keep `plugin-dist` as a jar module with plain `main()` classes. A Mojo wrapper can be added later in a separate `plugin-mojo` module.

### Two Java classes
- `PackageRuntime` — builds the zip for one target platform. Accepts target name and JDK home path. Pure file I/O + zip, no external processes except optionally running `--help` for smoke test on native platform.
- `PublishRelease` — orchestrates the full release sequence. Calls `PackageRuntime` directly (same JVM). Calls `git` and `gh` via `ProcessBuilder` for version bump, branch management, tagging, and GitHub Release creation/upload.

### JDK path via Maven property
`<jdk.semeru.linux-x64>` property in `plugin-dist/pom.xml` defaults to `/opt/installers/jdk-semeru/jdk-25.0.2+10`. Override on the command line if needed. This avoids hard-coding paths in Java source.

### Output location
`plugin-dist/target/dist/shipsmooth-tasks-{version}-linux-x64.zip`

### Version bump stays in PublishRelease
`mvn versions:set` is called by `PublishRelease` via `ProcessBuilder`, same as `release.sh` did. The caller just passes the new version string.

### retire release.sh and package-tasks-java.sh
Both are deleted (or moved to `plugin-devel/` as reference) once Task 4 completes successfully.

## Tasks

### Task 1: Verify existing release process end-to-end [Risk: Medium]

Before touching anything, establish that `release.sh` + `mvn compile -Pprod` produces a correct `build/` output and that the `releases` branch structure is as expected. This is the baseline we must not break until Task 5 completes.

Steps:
- Run `mvn compile -Pprod -P'!dev'` and inspect `build/` output (`.claude-plugin/`, `hooks/`, `dist/`, `scripts/`, `skills/`, `package.json`)
- Confirm `build/dist/session-start-config.json` has correct `version` and `cacheDir`
- Inspect the `releases` branch: check out locally, verify `dist/` layout matches what `release.sh` copies
- Confirm `gh release view v0.2.0` shows zero assets (documenting the known gap)
- Document any discrepancies in a deviation note — do not fix them here

This task produces no code changes. It is complete when we have a written record of the current state.

### Task 2: Convert plugin-dist to jar and write PackageRuntime [Risk: Low]

Change `<packaging>pom</packaging>` to `<packaging>jar</packaging>` in `plugin-dist/pom.xml`. Add `src/main/java/`. Write `io.bitken.shipsmooth.dist.PackageRuntime` with a `main()` that:

- Reads args: `<target>` and `<jdk-home>`
- Verifies `plugin-tasks-java/target/jlink-image/` exists and contains `bin/shipsmooth-tasks`
- Copies jlink image into staging dir as `runtime/`
- Writes `bin/shipsmooth-tasks` shell launcher (install-relative, `-Xquickstart`, `-Xshareclasses`, SCC under `${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/scc`)
- Zips staging dir to `plugin-dist/target/dist/shipsmooth-tasks-{version}-{target}.zip`
- On Linux-native: smoke-tests the staged launcher with `--help`

Launcher template (same as `package-tasks-java.sh` produces today):
```sh
#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL="$(cd "$DIR/.." && pwd)"
SCC_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/scc"
mkdir -p "$SCC_DIR"
exec "$INSTALL/runtime/bin/java" \
  -Xquickstart \
  -Xshareclasses:name=shipsmooth_v{VERSION},cacheDir="$SCC_DIR",nonfatal \
  -m io.bitken.shipsmooth.tasks/io.bitken.shipsmooth.tasks.TasksCli "$@"
```

Invocation:
```bash
mvn -pl plugin-dist -am compile exec:java \
  -Dexec.mainClass="io.bitken.shipsmooth.dist.PackageRuntime" \
  -Dexec.args="linux-x64 /opt/installers/jdk-semeru/jdk-25.0.2+10" \
  -Pprod -P'!dev'
```

### Task 3: Write PublishRelease [Risk: High]

*Depends on: 2*

Write `io.bitken.shipsmooth.dist.PublishRelease` with a `main()` that orchestrates:

1. Assert clean working tree (`git diff --quiet && git diff --cached --quiet`)
2. Assert tag `v{version}` does not exist locally or on remote
3. `mvn versions:set -DnewVersion={version} -DgenerateBackupPoms=false`
4. `git add` all `pom.xml` files, `git commit -m "chore: bump version to {version}"`
5. Record `MAIN_SHA` (`git rev-parse --short HEAD`)
6. `mvn compile -Pprod -P!dev` (full plugin build)
7. Call `PackageRuntime.run("linux-x64", jdkHome)` directly
8. `git checkout releases`
9. Delete and recreate `dist/` from `build/.claude-plugin`, `build/hooks`, `build/dist`, `build/scripts`, `build/skills`, `build/package.json`
10. `git add dist/`, `git commit`, `git tag v{version}`, `git push origin releases v{version}`
11. `gh release create v{version} --target releases --title v{version} --notes "..."`
12. `gh release upload v{version} plugin-dist/target/dist/shipsmooth-tasks-{version}-linux-x64.zip`
13. `git checkout {original-branch}`

Invocation:
```bash
mvn -pl plugin-dist -am compile exec:java \
  -Dexec.mainClass="io.bitken.shipsmooth.dist.PublishRelease" \
  -Dexec.args="0.3.0" \
  -Pprod -P'!dev'
```

### Task 4: Wire exec-maven-plugin into plugin-dist pom [Risk: Low]

*Depends on: 2*

Add `exec-maven-plugin` executions to `plugin-dist/pom.xml` so invocations shorten to:

```bash
# Package runtime zip only:
mvn -pl plugin-dist -am compile exec:java@package-runtime -Pprod -P'!dev'

# Full release:
mvn -pl plugin-dist -am compile exec:java@publish-release -Dshipsmooth.release.version=0.3.0 -Pprod -P'!dev'
```

Add `<jdk.semeru.linux-x64>` property defaulting to `/opt/installers/jdk-semeru/jdk-25.0.2+10`.

### Task 5: Bump to 0.3.0, run release, verify, retire shell scripts [Risk: High]

*Depends on: 1, 2, 3, 4*

- Run `PublishRelease` end-to-end for version 0.3.0
- Verify on GitHub: `v0.3.0` tag, `releases` branch updated, `shipsmooth-tasks-0.3.0-linux-x64.zip` asset present
- Smoke-test full user install flow: wipe `~/.cache/shipsmooth/runtime-0.3.0/`, trigger SessionStart, confirm runtime lands and `shipsmooth-tasks --help` works
- Delete `scripts/release.sh` and `scripts/package-tasks-java.sh` (or move to `plugin-devel/` as reference)
- Update `DEVELOPMENT.md` if it references the old scripts

## Out of scope
- Mac/Windows packaging including cross-platform launcher (v0.3.1+)
- Maven Mojo wrapping (future `plugin-mojo` module)
- Gemini release path (`release-gemini.sh` unchanged)
- Cross-platform `session-start.ts` URL selection (deferred; linux-x64 guard stays)