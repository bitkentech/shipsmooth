# plan-92 — remote SessionStart hook must not depend on CLAUDE_PLUGIN_ROOT

> **v2 — repointed.** v1 targeted "fetch latest build / version drift." Tracing the
> code showed the published plugin already render-stamps the matching version (no
> drift in the shipped artifact), and the *real* failure is that the published
> SessionStart hook locates its installer via `${CLAUDE_PLUGIN_ROOT}`, which Claude
> Code leaves **empty for SessionStart hooks** in the cloud env — so the install
> silently does nothing. v2 targets that. Per workflow, scope shift → version bump.

## Context

Feature (in the user's words): claude code remote should fetch latest build.
What that actually means once traced: **a `CLAUDE_CODE_REMOTE=true` (cloud) session
must reliably install the shipsmooth Java CLI runtime — and today it can fail to
install anything because the hook can't find its own installer script.**

### Two hooks, two behaviours

There are two distinct copies of the SessionStart hook:

1. **The repo's committed hook** — `.claude/hooks/session-start.sh`, wired by
   `.claude/settings.json` as `$CLAUDE_PROJECT_DIR/.claude/hooks/session-start.sh`.
   Runs for sessions on *this repo* (dev / self-host). **It is gated to remote only**
   — its first lines are:

   ```sh
   if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
     exit 0          # local sessions do nothing; it acts only when CLAUDE_CODE_REMOTE=true
   fi
   ```

   It locates its installer via `$SCRIPT_DIR`/`$REPO_ROOT` (`dirname "$0"`), **not**
   `CLAUDE_PLUGIN_ROOT`, and `CLAUDE_PROJECT_DIR` *is* set in cloud — so it is already
   `CLAUDE_PLUGIN_ROOT`-independent. Its only flaw is a hand-maintained
   `PLUGIN_VERSION="0.3.24"` literal that has drifted (repo is at 0.3.29).

2. **The published plugin hook** — `hooks/hooks.json` shipped inside the marketplace
   plugin, rendered by `HookCommandRenderer`. What a normal cloud user gets. Its
   command is:

   ```sh
   sh "${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth.sh" shipsmooth 0.3.27
   ```

   **Note the asymmetry:** the published command has **no `CLAUDE_CODE_REMOTE` guard**
   — it runs on *every* SessionStart, local or remote (`install-shipsmooth.sh`
   self-short-circuits when the runtime is already installed, which is how repeated/
   local sessions avoid re-downloading). The version (`0.3.27`) is **render-stamped
   from the build**, so it already matches the plugin — no drift there. The problem is
   `${CLAUDE_PLUGIN_ROOT}`.

### The real failure (cloud)

`${CLAUDE_PLUGIN_ROOT}` is documented as the plugin's install dir, but Claude Code
**does not set it for SessionStart hooks** — a confirmed, still-open bug
(anthropics/claude-code #27145, #39550, #42564, #59713, and many plugin-side reports
e.g. claude-mem #629, everything-claude-code #256). When it is empty, the shell
expands the command to:

```sh
sh "/hooks/install-shipsmooth.sh" shipsmooth 0.3.27   # → file not found → nothing installs
```

So in the cloud env, a marketplace user's SessionStart hook can **silently install no
runtime at all**. The skills then shell out to a `~/.cache/shipsmooth/<v>/bin/...`
path that was never created → every `[Local]` CLI command fails. This is the failure
behind the user's request, and we are treating the cloud failure mode as **real**
(decision: do not rely on `CLAUDE_PLUGIN_ROOT` in the published cloud hook).

The empty-`CLAUDE_PLUGIN_ROOT` symptom is specifically the **SessionStart-hook** bug,
so the failure surfaces in the cloud / `CLAUDE_CODE_REMOTE=true` environment — exactly
the scope the user is asking about. (The published command itself has no
`CLAUDE_CODE_REMOTE` gate, but the empty-var condition that breaks it is what the
remote SessionStart env produces.)

### Fix direction

The published hook command must locate `install-shipsmooth.sh` **without depending on
`${CLAUDE_PLUGIN_ROOT}` being set**, while still using it when it *is* set (normal
non-SessionStart contexts, and any env where the bug is fixed). The community-proven
shape is bash parameter-expansion with a reconstructed-cache-path fallback — and the
renderer already knows every piece of that path at stamp time (marketplace `bitkentech`,
plugin/repo name, version):

```sh
_R="${CLAUDE_PLUGIN_ROOT:-$HOME/.claude/plugins/cache/bitkentech/shipsmooth/0.3.27}"; \
  sh "$_R/hooks/install-shipsmooth.sh" shipsmooth 0.3.27
```

- When `CLAUDE_PLUGIN_ROOT` is set → unchanged behaviour.
- When empty (cloud SessionStart) → falls back to the reconstructed absolute cache
  path. Fully determined at render time; nothing hand-maintained.

This mirrors the existing **Windows** branch in `HookCommandRenderer`, which already
hardcodes the reconstructed cache root (`%USERPROFILE%\.claude\plugins\cache\
bitkentech\<repo>\<version>`) instead of trusting a plugin-root variable — so the
POSIX fix brings the two branches into parity rather than inventing a new pattern.

### What this plan does NOT do

- It does **not** add version discovery (`installed_plugins.json` parsing) or
  `releases/latest` fetching. v1 proposed those to "track latest," but the published
  artifact already stamps the matching version. Re-targeting the fetched version is a
  separate concern and out of scope here.
- It does **not** change the skills' pinned CLI path or the version semantics.

### Verification reality

The cloud `CLAUDE_PLUGIN_ROOT`-empty behaviour is taken as established (user-confirmed
+ documented bug cluster). The fallback is testable **without** a cloud session: a unit
test can run the rendered command string with `CLAUDE_PLUGIN_ROOT` unset and assert it
resolves to the reconstructed path; the renderer output is assertable in `HookCommand­
RendererTest`. Final confirmation in a real cloud session is a nice-to-have, not a
blocker for the code change.

### Backlog feature (Local mode — Core Invariant #3)

**Feature:** *Remote/cloud SessionStart hook installs the runtime without relying on
`CLAUDE_PLUGIN_ROOT`.* A `CLAUDE_CODE_REMOTE=true` session must successfully bootstrap
the shipsmooth Java CLI even when Claude Code leaves `CLAUDE_PLUGIN_ROOT` empty for
SessionStart hooks. Secondarily, the repo's own dev hook must stop hardcoding a drifted
version literal.

## Tasks

> Risk-sorted High → Low. Task 1 is the core fix (published hook
> `CLAUDE_PLUGIN_ROOT`-independence) — the highest-risk because it touches the render
> path every host's hook flows through. Task 2 fixes the repo dev-hook version drift.
> Task 3 documents + guards. Risk levels are **proposed** — pending human calibration.

### Task 1: Make the published POSIX hook command CLAUDE_PLUGIN_ROOT-independent [High]

In `HookCommandRenderer.posixCommand`, change the rendered command so it locates
`install-shipsmooth.sh` via a `${CLAUDE_PLUGIN_ROOT:-<reconstructed-cache-path>}`
fallback instead of a bare `${CLAUDE_PLUGIN_ROOT}`. The reconstructed path is built
from the marketplace org (`bitkentech`), plugin/repo name, and version the renderer
already has (mirror the existing Windows branch's `windowsCacheRoot`). Preserve exact
behaviour when `CLAUDE_PLUGIN_ROOT` is set. Update `HookCommandRendererTest` to assert
both branches (set → original path; unset → reconstructed path resolves). This is the
fix for the real cloud failure.

### Task 2: Fix the repo dev-hook version drift [Medium]

*Depends-on: 1*

`.claude/hooks/session-start.sh` hardcodes `PLUGIN_VERSION="0.3.24"` (drifted from the
repo's 0.3.29). This hook is **gated to `CLAUDE_CODE_REMOTE=true`** (it `exit 0`s
otherwise), so the fix only affects remote/cloud sessions on this repo — preserve that
guard exactly. Make the committed dev hook derive its version from the build's single
source of truth (`gradle.properties → plugin.version`) instead of a hand-typed literal,
so the self-host/dev cloud path installs the version matching the checked-out tree. The
dev hook already locates its installer via `$REPO_ROOT` (no `CLAUDE_PLUGIN_ROOT`
dependency), so this task is version-only.

### Task 3: Document the fallback + guard against regressing to bare CLAUDE_PLUGIN_ROOT [Low]

*Depends-on: 2*

Document (DEVELOPMENT.md / the renderer's javadoc) that the POSIX SessionStart command
must never use a bare `${CLAUDE_PLUGIN_ROOT}` because Claude Code leaves it empty for
SessionStart hooks (link the issue cluster). Add a guard — a renderer test or a simple
check — that fails if the rendered POSIX command contains `${CLAUDE_PLUGIN_ROOT}`
without the `:-` fallback, so the regression can't silently return.
