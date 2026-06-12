# Plan 78 — Drop `runtime-` prefix from cache version directory

## Context

The shipsmooth runtime is installed by the SessionStart hook to a versioned
subdirectory under the XDG cache:

```
~/.cache/shipsmooth/runtime-0.3.20/bin/shipsmooth
```

The `runtime-` prefix is redundant — the parent directory (`shipsmooth/`) already
names the product, and the version number alone is sufficient to identify the slot.
Dropping the prefix shortens paths and removes a wart that confuses users reading
docs or logs.

The SCC (JVM Shared Class Cache) was also being placed in a global per-product
directory (`~/.cache/shipsmooth/scc`) shared across all installed versions. This
caused subtle conflicts when two versions were installed side-by-side. The fix moves
SCC under the version install dir (`$INSTALL/scc`) so each version is fully
self-contained.

Both changes were implemented by Codex in a prior session without plan/task
scaffolding. This plan retroactively records that work and closes it out correctly.

## Goals

1. Cache path is `~/.cache/shipsmooth/{version}/bin/shipsmooth` (no `runtime-` prefix).
2. SCC directory is `$INSTALL/scc` (per-version, not global).
3. All tests pass, docs/scripts updated.

## Out of scope

- Windows SCC path (already fixed as part of the SCC change).
- Any migration of existing installs (old `runtime-{version}/` dirs are simply
  ignored; the next SessionStart installs into the new path).

---

### Task 1: Drop `runtime-` prefix from version dir + move SCC under install [Low]

Retroactive task: all changes already exist as unstaged edits on main.

Files changed:
- `skills/pkg/src/main/java/io/bitken/ss/resources/Os.java` — `cliBinPath` removes `runtime-` prefix
- `skills/pkg/src/main/resources/install-shipsmooth.sh` — `RUNTIME_DIR` uses bare version
- `skills/pkg/scripts/tasks/session-start.ts` — `runtimeDir` uses bare version
- `packaging/src/main/java/io/bitken/ss/dist/PackageRuntime.java` — SCC moved to `$INSTALL/scc`
- Tests updated: `OsTest`, `PosixBootstrapIntegrationTest`, `TargetIntegrationTest`, `PackageRuntimeTest`, `session-start.test.ts`, `install-download.test.ts`
- Docs/scripts updated: `DEVELOPMENT.md`, `EXPERIMENTAL.md`, `devtools/scripts/smoke-gemini.sh`, `gradle.properties` (comment only)
