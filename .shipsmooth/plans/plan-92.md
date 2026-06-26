# plan-92 — claude code remote should fetch latest build

## Context

Feature (in the user's words): claude code remote should fetch latest build

### The bug

The "claude code remote" mechanism is `.claude/hooks/session-start.sh`. It runs
**only** when `CLAUDE_CODE_REMOTE=true` (cloud/remote agents, not local sessions),
and hardcodes a **stale, pinned** version:

```sh
PLUGIN_VERSION="0.3.24"   # repo is at 0.3.29 — drifted 5 patches
```

Flow: the hook passes `0.3.24` to
`harness/shared/src/main/resources/install-shipsmooth.sh`, which builds the GitHub
release URL `.../releases/download/v0.3.24/shipsmooth-0.3.24-<platform>.zip` and
installs into `~/.cache/shipsmooth/0.3.24/`. So a remote agent fetches an **old**
runtime build, not the latest. The literal is a hand-copied constant that drifts
on every release.

### Two independently-versioned things — don't conflate

1. **The plugin** — `.claude/settings.json` enables `shipsmooth@bitkentech` with
   **no version pin**, resolved against the `bitkentech/claude-plugins` marketplace.
   Claude Code installs whatever the marketplace HEAD points at, at session start.
   So the plugin **already** tracks "latest." Not the problem.
2. **The Java CLI runtime** (jlink build at `~/.cache/shipsmooth/<v>/bin/shipsmooth`)
   — downloaded by `session-start.sh`, pinned to the stale `0.3.24`. **This** is
   the problem. The skills shell out to a *version-pinned* CLI path
   (`~/.cache/shipsmooth/0.3.27/bin/...`), so the runtime the hook installs must
   match the path the skills call.

### Cloud-environment facts (verified against docs, 2026-06)

- **Cloud runs the committed repo hook, not a local cache copy.** "Cloud sessions
  start from a fresh clone of your repository. Anything committed to the repo is
  available; anything configured only on your machine is not." → the file that
  executes in cloud is the repo's committed `.claude/hooks/session-start.sh` — the
  exact `0.3.24` literal flagged above. (Confirms which file matters.)
- **Plugins *are* installed in cloud**, at session start, from the declared
  marketplace (needs network to the marketplace source). So the resolved plugin
  version is determinable at runtime in cloud.
- **Node is guaranteed in cloud** — base image ships Node 20/21/22 via nvm (+ npm,
  jq-able tooling), also OpenJDK 21 + Gradle, Python, etc. So JSON parsing in the
  hook is feasible *in cloud*. (But the hook is shared with non-cloud remotes, so
  keep the bootstrap POSIX-sh and don't hard-depend on Node — the existing
  node-free design of `install-shipsmooth.sh` stands.)
- **`CLAUDE_PLUGIN_ROOT` self-location is unavailable here** — it is documented but
  **not set for SessionStart hooks** (anthropics/claude-code #27145, open cluster
  #24529/#36585/#42564), *and* the committed repo hook isn't in a version-named
  dir anyway. So the hook cannot learn its version from its own path.

### Decision: match the installed plugin (not `releases/latest`)

The goal is "the CLI for **exactly the plugin that's running**," not "newest release
tag on GitHub." Those usually coincide but can skew (marketplace HEAD behind a
fresh release, yanked plugin, release published before the manifest bumps). If the
hook fetched `releases/latest` while CC installed an older marketplace HEAD, the
runtime would be **newer** than the plugin calling it, and the skills' pinned CLI
path would point at a dir the hook never created — the path mismatch just moves,
it doesn't go away.

`settings.json` names the plugin (`shipsmooth@bitkentech: true`) but does **not**
carry the resolved version — so "match the installed plugin" requires a discovery
step. The resolved version lives in:

- `~/.claude/plugins/installed_plugins.json` → `shipsmooth@bitkentech → version`
  (authoritative; Node/jq-parseable)
- `~/.claude/plugins/cache/bitkentech/shipsmooth/<version>/` (version is the dir name)

**Recommended approach:** the hook reads the resolved plugin version from
`installed_plugins.json` (cache-dir glob as a secondary read), fetches the matching
CLI runtime, and installs to `~/.cache/shipsmooth/<that-version>/` — which is then
the same path the skills already call. **`releases/latest` is the fallback only**
(robust because it depends on nothing on disk: `/releases/latest/download/<asset>`
resolves with HTTP 200 — verified — but the asset name embeds the version, so it
needs either a version-less asset published at release time, or a redirect-parse of
`/releases/latest` → `tag_name` first).

### De-risk first (the one open empirical risk)

Everything above is settled except one thing the docs can't confirm: **does
`~/.claude/plugins/installed_plugins.json` exist and contain
`shipsmooth@bitkentech`'s resolved version at the moment our SessionStart hook fires
in the `CLAUDE_CODE_REMOTE=true` sandbox?** Cloud docs say plugins are installed at
session start, so it's *very likely yes*, but ordering (hook-fire vs. plugin-install)
is unverified. This gates the whole design and must be the first, highest-risk task:
probe in a real cloud session, then branch — file present → read-and-match; absent →
`releases/latest` fallback.

### Backlog feature (Local mode — Core Invariant #3)

**Feature:** *Remote/cloud sessions install the CLI runtime matching the running
plugin.* A `CLAUDE_CODE_REMOTE=true` session must bootstrap the shipsmooth Java CLI
at the version of the plugin that Claude Code actually installed, with no
hand-maintained version literal. (Recorded in the `<backlog-issue>` element when the
task XML is generated.)

_Open design questions carried into the tasks:_
- _Whether the repo's own copy of the hook (self-host / dev) needs a different
  version source than the marketplace-installed copy, or both share discovery +
  fallback. (Task 4 settles this.)_
- _Whether the skills should stop pinning the CLI path (decouple) or keep pinning
  and trust the hook to install the matching version. (Resolved: keep pinning; the
  hook installs the matching version so the pinned path resolves — Task 3.)_

## Tasks

> Risk-sorted High → Low. Task 1 is the de-risk probe that gates the design;
> Task 2 builds the version-resolution core; Task 3 wires it into the bootstrap;
> Task 4 reconciles the repo-copy/dev hook; Task 5 removes the dead literal and
> documents. Risk levels below are **proposed** — pending human calibration.

### Task 1: Probe installed_plugins.json availability in cloud [High]

De-risk the one fact the docs can't confirm: in a real `CLAUDE_CODE_REMOTE=true`
cloud session, does `~/.claude/plugins/installed_plugins.json` exist and contain
`shipsmooth@bitkentech`'s resolved version **at the moment the SessionStart hook
fires**? Also confirm the cache-dir form
`~/.claude/plugins/cache/bitkentech/shipsmooth/<version>/` is present. Capture the
actual on-disk shape (the hook-fire-vs-plugin-install ordering is the risk). The
result branches the whole design: present → read-and-match (Task 2); absent →
`releases/latest` fallback becomes primary. Output is a recorded finding, not
production code.

### Task 2: Version-resolution helper (read installed plugin, fallback to latest) [High]

*Depends-on: 1*

A POSIX-sh resolver that returns the version string to install: first read it from
`installed_plugins.json` (Node/jq parse — Node is guaranteed in cloud); on
absence/parse-failure, fall back to resolving GitHub `releases/latest` (redirect
→ `tag_name`, or a version-less asset). Must degrade safely and emit only to stderr
for logs (info to stdout per repo convention). This is the core logic — prove it
against the real file shape from Task 1.

### Task 3: Wire resolver into the bootstrap + match the skill's pinned path [Medium]

*Depends-on: 2*

Replace `install-shipsmooth.sh`'s reliance on a passed-in pinned version with the
resolver's output, installing to `~/.cache/shipsmooth/<resolved>/` — the same path
the skills already call (keep skill pinning; the hook installs the matching version
so the pinned path resolves). Preserve the existing node-free download/unzip/atomic-mv
and the `SS_URL_BASE` test override. Confirm the "already installed" short-circuit
still works per resolved version.

### Task 4: Reconcile the committed repo hook (session-start.sh) [Medium]

*Depends-on: 3*

`session-start.sh` currently passes a hardcoded `PLUGIN_VERSION`. Decide and
implement how the committed repo copy (which runs in cloud and self-host, and is
**not** in a version-named dir) obtains the version: route it through the Task 2
resolver rather than a literal. Ensure the dev/self-host path still works when there
is no marketplace install (resolver falls back cleanly).

### Task 5: Remove the dead literal, add drift guard, document [Low]

*Depends-on: 4*

Delete the `PLUGIN_VERSION="0.3.24"` literal and any now-dead pinned-version plumbing.
Add a guard/test that fails if a hand-maintained version constant reappears in the
hook path. Update DEVELOPMENT.md / relevant docs to describe the new
resolve-from-installed-plugin behaviour and the `releases/latest` fallback.
