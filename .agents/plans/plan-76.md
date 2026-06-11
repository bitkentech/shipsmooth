# plan-76 — Node-free Posix bootstrap for the SessionStart hook

## Context

**Backlog feature:** macOS/Linux users must be able to use the shipsmooth plugin
without a pre-installed Node.js. (No external Linear issue; tracked here.)

macOS ships **no Node.js** by default (and never has — Apple has been *shrinking*
the default userland, e.g. removing the bundled Python 2 in 12.3). The plugin's
`SessionStart` hook on Posix is:

```
node "${CLAUDE_PLUGIN_ROOT}/dist/session-start.js"
```

so on a stock Mac the hook fails with `node: command not found` and the jlink
runtime is never bootstrapped. The irony: `session-start.ts` exists precisely so
users need no JDK — but the bootstrap itself depends on Node, which it cannot
bootstrap.

**Windows already solved this.** `Os.Windows.hookCommand()` writes an
`install-runtime.bat` and returns a `cmd.exe /C` invocation — Node is never
invoked on Windows. This plan applies the same model to Posix: generate an
`install-shipsmooth.sh` and have the hook run it with `sh`.

### Spike result (pre-plan, throwaway `.agents/tmp/install-runtime-spike.sh`)

A ~90-line POSIX `sh` reimplementation of `session-start.ts` was validated on
Linux against the real `0.3.18` release. Using only stock-macOS tools
(`sh`, `curl`, `unzip`, `uname`, `chmod`, `mktemp`, `mv`):

- `unzip` restores the stored unix exec bits **by default** — `jspawnhelper` came
  out `-rwxr-xr-x` with no manual chmod (the AdmZip `keepOriginalPermission`
  equivalent). This was the #1 risk; resolved on Linux's Info-ZIP.
- `curl -fsSL --retry` absorbs the redirect-follow + retry logic that
  `session-start.ts` hand-rolls (~80 lines); GitHub→S3 redirect handled
  transparently.
- Idempotency (`[ -x "$bin" ]`), atomic install (`mv` from `.tmp`), and the
  unsupported-platform error path all pass.

**Authoritative tool availability on stock macOS** (Apple opensource releases +
base `/usr/bin`): `sh`, `curl`, `unzip`, `tar`, `uname`, `chmod`, `mktemp`, `mv`
are **present**; `wget` is **absent** (Homebrew-only) — so the script uses `curl`,
never `wget`. `/bin/bash` is frozen at 3.2, default shell is `zsh` → target is
strict `#!/bin/sh`, no bashisms.

### Open verification deferred to a real Mac (post-release, by human)

macOS ships Info-ZIP `unzip` too, so behaviour should match, but the exec-bit
restoration could not be proven on a Mac in this environment. After the release
that ships this change, the human will run, on macOS:

```sh
SS_CACHE_DIR=/tmp/ss-spike sh <plugin>/hooks/install-shipsmooth.sh  # via the real hook
ls -l /tmp/ss-spike/runtime-<ver>/runtime/lib/jspawnhelper          # expect -rwxr-xr-x
/tmp/ss-spike/runtime-<ver>/bin/shipsmooth plan resume --plan 75    # expect exit 0, no EACCES
```

This is acknowledged as a residual risk on Task 1, not a blocker for shipping.

## Goals / Non-goals

**Goals**
- Posix `SessionStart` hook runs without Node on macOS and Linux.
- Mirror the proven Windows design: generator writes `install-shipsmooth.sh`, hook
  invokes it with `sh`.
- Keep `session-start.ts` shipped as a non-invoked backup for one release cycle
  (de-risking: if the sh path misbehaves on a real Mac, the TS is a known-good
  fallback we can re-point the hook to without a code change beyond the hook
  string).

**Non-goals**
- Deleting `session-start.ts` / its Vitest suite (kept as backup this cycle;
  removal is a follow-up plan once macOS is confirmed).
- Changing the Windows path (already Node-free).
- The pre-extracted-runtime model (download-on-demand, matching today's TS, is
  retained — smaller plugin package, and the spike proved curl works).

## Design

### Where the hook string comes from today

`build.gradle.kts` passes `pluginHookCommand` per variant →
`plugin.hook.command` system property → `Os.Posix.hookCommand()` returns it
verbatim. Two placeholder forms are in play:
- claude variants: `${CLAUDE_PLUGIN_ROOT}`
- gemini variants: `${extensionPath}`

### Target design (mirror Windows)

`Os.Posix.hookCommand(hooksDir, repoName, pluginName, version)` will:
1. Write `install-shipsmooth.sh` into `hooksDir` (the generated, parameterised
   script — baked with `pluginName`/`version`, no per-variant placeholder inside).
2. Return the hook invocation string `sh "<plugin-root>/hooks/install-shipsmooth.sh"`.

**The plugin-root placeholder problem.** The `.bat` path on Windows hard-codes a
`%USERPROFILE%\.claude\plugins\cache\...` absolute path. On Posix the hook must
work for *both* claude (`${CLAUDE_PLUGIN_ROOT}`) and gemini (`${extensionPath}`).
Resolution: keep `pluginHookCommand` as the **carrier of the plugin-root
placeholder**, but change its value to the `sh` form, e.g.
`sh "${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth.sh"` /
`sh "${extensionPath}/hooks/install-shipsmooth.sh"`. `Os.Posix.hookCommand()` still
writes the script file as a side-effect (like Windows writes the `.bat`) and
returns the passed-in `sh ...` string. This preserves the existing
claude/gemini placeholder split with zero new branching and keeps the script
itself placeholder-free (it self-resolves cache dir from `$XDG_CACHE_HOME`/`$HOME`).

### The script (`install-shipsmooth.sh`)

Productionised from the spike: strict POSIX, `curl -fsSL --retry 2`, the
version/pluginName baked at render time (read from the same model values the
`.bat` uses). Cache resolution mirrors `resolveCache()` in `session-start.ts`
(`${XDG_CACHE_HOME:-$HOME/.cache}/<name>`). No `jlinkDir`/local-build branch in
the first cut (prod hook only — dev builds still use the TS path with a local
jlink dir; see Task 4).

### Dev-build caveat

Dev variants (`claudeDev`, `geminiDev`) pass a real `jlinkDir` and rely on
`session-start.ts`'s "install from local build" branch (`fs.cpSync(jlinkDir,…)`).
The sh script does **not** replicate that branch in this plan. So **dev variants
keep the `node …session-start.js` hook**; only **prod** variants switch to the sh
hook. This is deliberate: prod is what real macOS users install; dev is the
maintainer's box where Node is present. (Task 4 makes this split explicit and
tested.) Revisit unifying dev onto sh in a follow-up.

## Tasks

### Task 1: POSIX `install-shipsmooth.sh` generator + script [High]

*Depends-on:*

Highest spiral risk: this is the core logic and carries the residual macOS
exec-bit uncertainty. Add an `installRuntimeShContent(pluginName, version)`
producer to `Os.Posix` (analogous to `installRuntimeBatContent`) that emits the
productionised spike script (strict `#!/bin/sh`, `curl -fsSL --retry 2`,
`unzip`, idempotency, atomic `mv`, supported-platform guard). Java-level test:
assert the generated script contains the platform-detect, `curl -fsSL`, `unzip`,
the baked version, and is `#!/bin/sh`. Behavioural proof reuses the spike harness
on Linux (exec bits + launcher runs). macOS exec-bit confirmation deferred
(Context).

### Task 2: `Os.Posix.hookCommand()` writes the script (side-effect) [Medium]

*Depends-on:* 1

Mirror `Os.Windows.hookCommand()`: write `install-shipsmooth.sh` into `hooksDir`
and return the passed-in hook string (no longer the bare default). Update the
`Os.Posix.hookCommand` default and signature use. Test: after `hookCommand()`,
`hooksDir/install-shipsmooth.sh` exists, is `#!/bin/sh`, and the returned string is
the `sh "...install-shipsmooth.sh"` invocation. Update `OsTest` /
`TargetIntegrationTest` expectations (they currently assert the posix hook
references `session-start.js`).

### Task 3: Wire prod variants' `pluginHookCommand` to the sh invocation [Medium]

*Depends-on:* 2

In `build.gradle.kts`, change `claudeProdSpec`/`geminiProdSpec`
`pluginHookCommand` from `node "…/dist/session-start.js"` to
`sh "${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth.sh"` /
`sh "${extensionPath}/hooks/install-shipsmooth.sh"`. Leave **dev** specs on the
`node …` command (Task 4 covers the split). Verify rendered
`build/render/claude-prod/hooks/hooks.json` and `gemini-prod` carry the sh hook
and that `install-shipsmooth.sh` is emitted alongside.

### Task 4: Keep dev on Node, lock the split with a test [Low]

*Depends-on:* 3

Make the dev-vs-prod hook split explicit and regression-proof: dev variants
(`claudeDev`, `geminiDev`) keep `node "…/dist/session-start.js"` (they need the
TS local-jlink branch); prod variants use sh. Add/adjust a render test asserting:
prod hooks.json → `sh …install-shipsmooth.sh` (no `session-start.js`); dev
hooks.json → `node …session-start.js`. Confirms `session-start.ts` still ships
(backup) and is still copied into dev `dist/`.

### Task 5: Docs + version bump + release-readiness note [Low]

*Depends-on:* 4

Document the Posix bootstrap (no Node required on macOS/Linux for prod installs)
wherever the install story lives. Note the deferred macOS verification step
(Context) in the plan/release notes so the human runs it post-release. Bump the
patch version per the release process (human cuts the actual release). Do **not**
run `publishRelease`.

## Risk summary (pre-calibration defaults)

| Task | Default risk | Why |
|---|---|---|
| 1 | High | Core bootstrap logic; residual macOS exec-bit uncertainty. |
| 2 | Medium | Generator side-effect + breaks existing hook-string test expectations. |
| 3 | Medium | Multi-variant gradle wiring; parity-sensitive (claude vs gemini placeholder). |
| 4 | Low | Test-locking an already-decided split. |
| 5 | Low | Docs + version bump. |

Dependency note: 1 → 2 → 3 → 4 → 5 is a hard chain, so risk-sorted order already
matches dependency order (no Low task blocks a High one out of sequence).
