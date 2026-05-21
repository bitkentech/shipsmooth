# Plan 54: Windows plugin without system Node.js

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

The Claude Code marketplace `marketplace.json` schema has **no `platform`
field**. There is no built-in way to restrict a plugin entry to a specific OS.

The solution is to publish **two named plugin entries**: `shipsmooth` (existing,
Unix) and `shipsmooth-windows` (new). Task 1 de-risked the PowerShell hook
approach. However the investigation surfaced a signing/GPO problem that makes
PowerShell unreliable on corporate machines (see Task 1 addendum). The npm
bundling approach (Task 2) is the current candidate for de-risking.

### Why not a single-entry fallback (e.g. `node || powershell`)?

Shell-form `||` fallback in `hooks.json` is unreliable: the failed `node`
invocation produces stderr that surfaces as a hook error to the user before
the fallback runs. There is no clean way to suppress it.

### Why not require users to install Node.js?

Silent failure with no actionable error is a poor user experience. The plugin
should work out of the box on a fresh Windows machine.

### Why not use Claude Code's bundled Node.js?

Claude Code does not expose a bundled Node.js. Verified against documentation:
`type: "command"` hooks always resolve via system PATH. There is no native JS
hook type.

## Task 1 addendum — smoke test findings (2026-05-21)

Smoke test ran successfully on Windows 10/11 (personal machine). Key findings:

- **Hook location:** Hook belongs in `hooks/hooks.json` at plugin root, not in
  `plugin.json` and not as a `hooks` override in `marketplace.json`. The
  `marketplace.json` hooks-override approach in the original Decision above was
  wrong.
- **Working command:** `powershell.exe -ExecutionPolicy Bypass -File "${CLAUDE_PLUGIN_ROOT}/dist/session-start.ps1"` (no `-NonInteractive` needed).
- **`CLAUDE_PLUGIN_ROOT`** resolves to the plugin source dir for local installs;
  will resolve to cache dir for git-ref installs — expected.
- **PS 5.1 confirmed:** `Invoke-WebRequest -UseBasicParsing` works fine.
- **Code signing:** Script must be signed on default Windows policy. Self-signed
  cert works locally; `-ExecutionPolicy Bypass` bypasses process-level policy.

**Blocking issue discovered — GPO:** On enterprise/corporate Windows machines
(the majority of Windows developer machines), IT admins enforce execution policy
via Group Policy. When GPO is active, `-ExecutionPolicy Bypass` is **silently
ignored**. PowerShell scripts will not run regardless of the flag. A proper
code-signing certificate (~$300–500/yr EV cert) would be needed to work under
`AllSigned` GPO policy, with re-signing required after every script edit.

This makes the PowerShell approach unsuitable as the primary distribution path.
Tasks 2–5 (as originally written) are **suspended** pending Task 2 (npm
de-risk).

### Alternatives considered and rejected

- **`.cmd` / batch script** — no signing, no GPO issues, `curl.exe` + `tar.exe`
  inbox since Windows 10 1803. Rejected because `${CLAUDE_PLUGIN_ROOT}`
  expansion inside `cmd.exe /C "..."` is unverified, and batch JSON parsing is
  painful.
- **Native `.exe` (Go/Rust)** — no policy issues, but adds a compiled binary,
  cross-compilation build step, and SmartScreen friction.
- **Bundle jlink in git repo** — 48 MB binary committed to git, grows with every
  release. Rejected due to repo bloat.

### npm bundling approach — ELIMINATED (2026-05-21)

Tested end-to-end. The approach works on Linux/Mac but **fails on Windows** —
which is the only platform it needs to support.

**What was tested:**
- Published `@pramodbiligiri/shipsmooth-windows-smoke` (49 MB tarball, full
  win32-x64 jlink image) to npmjs.com. Artifact at `build-windows/`.
- Install on Linux via `/plugin install shipsmooth-windows@pramodbiligiri`
  succeeded instantly. Full package extracted to
  `~/.claude/plugins/cache/pramodbiligiri/shipsmooth-windows/0.3.10/` with all
  209 files including `runtime/bin/shipsmooth-tasks.cmd`. `CLAUDE_PLUGIN_ROOT`
  confirmed correct.
- Install on Windows failed: `Failed to install npm package: Command 'npm' not
  found or is in an unsafe location`.

**Root cause:** Claude Code's npm source installer shells out to the system
`npm` binary. Windows users without Node.js don't have `npm` — which is
exactly the problem we're trying to solve. The npm source type is circular: it
requires the very thing the plugin is meant to replace.

**Remaining options:**

1. **`.cmd` / batch hook with download** — `cmd.exe /C` invokes the hook, uses
   `curl.exe` + `tar.exe` (inbox since Windows 10 1803) to download and extract
   the jlink zip at first session start. No signing, no GPO issues. The
   `${CLAUDE_PLUGIN_ROOT}` expansion inside `cmd.exe` is still unverified.

2. **git source with jlink in repo** — 48 MB binary committed to git, Claude
   Code fetches it on `/plugin install` via git (no npm needed). Repo bloat is
   the cost; Git LFS compatibility with Claude Code's installer is unknown.

3. **Accept Node.js as a prerequisite** — document that Windows users need
   Node.js installed. The existing Unix hook works unchanged; the Windows entry
   just has the same hook. Simplest but worst UX.

Current lean: option 1 (`.cmd` hook). Needs a smoke test to verify
`${CLAUDE_PLUGIN_ROOT}` expansion in `cmd.exe /C "..."` on Windows.

## Backlog issue

PB-52 (windows release support) — this plan delivers the missing piece: a
working `SessionStart` hook on Windows without system Node.js.

## Tasks

### Task 1: Smoke-test the Windows wiring end-to-end [Medium]

**Status: complete.** See Task 1 addendum in the Decision section above.

Smoke test artifact lives at `.agents/tmp/win-smoke-test/` and
`~/tmp/shipsmooth-windows-working/` (Windows machine). Hook fired correctly,
`CLAUDE_PLUGIN_ROOT` confirmed, PS 5.1 confirmed. GPO signing issue discovered
— PowerShell approach suspended in favour of npm bundling (Task 2).

### Task 2: De-risk npm bundling approach [Medium]

**Status: complete — approach eliminated.**

Published `@pramodbiligiri/shipsmooth-windows-smoke` (49 MB, full win32-x64
jlink image) to npmjs.com. Linux install succeeded and confirmed full package
extraction into `CLAUDE_PLUGIN_ROOT`. Windows install failed with `npm not
found` — circular dependency on the very tool users lack. npm source type is
not viable for this use case. See npm bundling addendum in Decision section.

Artifact remains at `build-windows/` for reference.

---

*The following tasks were written for the PowerShell approach and are
**suspended** pending Task 2 outcome. They will be rewritten once the npm
approach is confirmed.*

### Task 3 (suspended): Write session-start.ps1 [Low]

*Depends-on: 1,2 — suspended, see above.*

### Task 4 (suspended): Update marketplace.json with shipsmooth-windows entry [Low]

*Depends-on: 3 — suspended, see above.*

### Task 5 (suspended): CI verification [Low]

*Depends-on: 4 — suspended, see above.*
