# Plan 54: Windows SessionStart hook without system Node.js

## Context

Plans 52 and 53 added `win32-x64` runtime support and fixed the Windows cache
path. However, the `SessionStart` hook that installs the Java runtime is wired
as:

```json
"command": "node \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\""
```

This requires `node` to be on `PATH`. On Windows, Claude Code ships its own
bundled Node.js for internal use but does not expose it on the system `PATH`.
Users without a separately installed Node.js get a silent hook failure on every
session start — the Java runtime is never downloaded and the plugin is
effectively broken.

## Problem investigation summary

- Node.js SEA (Single Executable Application) would produce a ~90 MB `.exe` —
  far too large to ship in the plugin zip.
- Claude Code's `hooks.json` has no `platform` field. There is no native way
  to specify different hook commands per OS.
- The `shell` field accepts `"bash"` or `"powershell"` but applies globally;
  `"powershell"` behaviour on Linux/macOS is undefined/risky.
- PowerShell 5.1 is inbox on every Windows 10/11 machine and can perform HTTP
  download + zip extract natively with zero additional dependencies.
- The cleanest solution is a **build-time platform split**: produce a separate
  Windows plugin zip at release time whose `hooks.json` points to a
  `session-start.ps1` instead of `session-start.js`.

## Decision

Ship a `session-start.ps1` that is a faithful port of `session-start.ts`
(Windows-only logic: download zip from GitHub releases, extract, verify).
At Maven build time, produce a second artifact — `build-win/` — whose
`hooks.json` uses `powershell.exe -NonInteractive -File
"${CLAUDE_PLUGIN_ROOT}/dist/session-start.ps1"` as the `SessionStart`
command. The existing Unix build (`build/`) is unchanged.

The Windows artifact is uploaded to the Claude Code marketplace alongside the
standard artifact, tagged for `win32` platforms.

### Why not a single-entry fallback (e.g. `node || powershell`)?

Shell-form `||` fallback in `hooks.json` is unreliable: the failed `node`
invocation produces stderr that surfaces as a hook error to the user before
the fallback runs. There is no clean way to suppress it.

### Why not a `.cmd` wrapper?

A `.cmd` file can try `node` then fall back to PowerShell, but Claude Code on
Linux/macOS cannot invoke `.cmd` files — so a single hook entry pointing to
`.cmd` breaks Unix users. A separate Windows `hooks.json` is required either
way.

### Why not require users to install Node.js?

Silent failure with no actionable error is a poor user experience. The plugin
should work out of the box on a fresh Windows machine.

## Backlog issue

PB-52 (windows release support) — this plan delivers the missing piece: a
working `SessionStart` hook on Windows without system Node.js.

## Tasks

### Task 1: Finalise approach and resolve open questions [Medium]

Before writing any code, nail down the three open questions that affect the
implementation shape:

1. **Marketplace platform targeting** — does the Claude Code marketplace
   `plugin.json` / manifest support a `platform` field that restricts a plugin
   version to Windows only? If yes, the Windows zip can be published as a
   separate platform-targeted variant. If no, users must manually select the
   right zip, which changes the UX story.

2. **`hooks.json` `shell: "powershell"` on Linux/macOS** — empirically confirm
   (via the Claude Code source or a test install) whether setting
   `"shell": "powershell"` on a non-Windows platform causes an error, a silent
   no-op, or falls back to bash. If it silently no-ops, a single `hooks.json`
   with `shell: "powershell"` pointing to the `.ps1` might work on Windows and
   harmlessly do nothing on Unix (with the `.js` hook removed).

3. **PowerShell execution policy** — confirm whether `powershell.exe
   -NonInteractive -File script.ps1` is blocked by the default Windows
   execution policy (`Restricted` on some enterprise installs). The
   `-ExecutionPolicy Bypass` flag may be needed in the hook command.

Deliverable: a short decision record (added to this plan file as an addendum)
confirming the chosen wiring approach, so Tasks 2–4 can proceed without
ambiguity.

### Task 2: Write session-start.ps1 [Low]

*Depends-on: 1*

Port `session-start.ts` to PowerShell. The script must:

- Read `session-start-config.json` from `$PSScriptRoot\..\dist\` (version,
  jlinkDir).
- Resolve the cache dir: `$env:LOCALAPPDATA\shipsmooth` (matching plan-53
  logic; fall back to `$env:USERPROFILE\AppData\Local\shipsmooth` if
  `LOCALAPPDATA` is unset).
- Exit 0 immediately if `runtime-{version}\bin\shipsmooth-tasks.cmd` already
  exists (idempotent).
- Build the download URL:
  `https://github.com/bitkentech/shipsmooth/releases/download/v{version}/shipsmooth-tasks-{version}-win32-x64.zip`
- Download with redirect-following and one retry on HTTP 5xx.
  Use `Invoke-WebRequest` (PS 3+) or `[System.Net.WebClient]` as fallback.
- Extract to a `.tmp` staging dir using `Expand-Archive`.
- Verify `bin\shipsmooth-tasks.cmd` exists in the extracted tree.
- Rename staging dir to final dir (`Move-Item`).
- Write progress messages to stderr; exit 1 with a message on any error.
- Skip the `jlinkDir` dev-copy path (Windows dev builds are not a supported
  scenario yet; document this explicitly).

No unit test framework is available for PowerShell in this repo. Instead,
write a manual smoke-test script `.agents/tmp/test-session-start.ps1` that
mocks the config and invokes the installer against a local test server or a
known-good release URL. Document how to run it in comments at the top.

### Task 3: Wire the Windows build in Maven [Low]

*Depends-on: 2*

Add a `windows` Maven profile (or extend the `prod` profile) that:

- Sets `build.outputDir` to `build-win/`.
- Generates a `hooks.json` with the PowerShell command (exact form TBD from
  Task 1).
- Copies `session-start.ps1` and `session-start-config.json` into
  `build-win/dist/`.
- Does **not** copy `session-start.js` or `adm-zip-bundle.js` (not needed on
  Windows).
- Reuses all other build artefacts (skills, plan files, etc.) unchanged.

The existing `prod` build must remain unmodified — Unix users must not be
affected.

### Task 4: CI and release integration [Low]

*Depends-on: 3*

Update the GitHub Actions release workflow to:

- Build the Windows artifact (`mvn -P windows ...`) alongside the existing
  Unix artifact.
- Upload `build-win/` as a separate release asset (or marketplace submission)
  named to distinguish it from the Unix build.
- Verify the Windows zip contains `session-start.ps1` and the correct
  `hooks.json` command string (grep check in CI).
