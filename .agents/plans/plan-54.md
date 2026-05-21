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

The Claude Code marketplace `marketplace.json` schema has **no `platform`
field**. There is no built-in way to restrict a plugin entry to a specific OS.

The solution is to publish **two named plugin entries in the same
`marketplace.json`**: `shipsmooth` (existing, Unix) and `shipsmooth-windows`
(new). Both point to the same git ref in the same repo. The difference is
entirely in the `hooks` override in the Windows entry, which replaces the
`node` command with `powershell.exe`. No separate build artifact or Maven
profile is needed — the `.ps1` file just needs to exist in `dist/` in the
repo at the tagged ref.

```json
{
  "name": "bitkentech",
  "plugins": [
    {
      "name": "shipsmooth",
      "source": { "source": "github", "repo": "bitkentech/shipsmooth", "ref": "v{version}" },
      "description": "Agent coding workflow...",
      "category": "development"
    },
    {
      "name": "shipsmooth-windows",
      "source": { "source": "github", "repo": "bitkentech/shipsmooth", "ref": "v{version}" },
      "description": "Agent coding workflow (Windows native — use this instead of shipsmooth on Windows without a system Node.js installation).",
      "category": "development",
      "hooks": {
        "SessionStart": [{
          "hooks": [{
            "type": "command",
            "command": "powershell.exe -NonInteractive -ExecutionPolicy Bypass -File \"${CLAUDE_PLUGIN_ROOT}/dist/session-start.ps1\""
          }]
        }]
      }
    }
  ]
}
```

`-ExecutionPolicy Bypass` is included proactively to handle enterprise
installs where the default policy is `Restricted`; it only scopes to the
spawned process.

### Open question: hooks merge behaviour under strict mode

By default (`strict: true`), a marketplace entry's `hooks` field is merged
with `plugin.json`'s hooks. If `plugin.json` already declares a `SessionStart`
hook (the `node` command), both hooks would fire on Windows — which is wrong.
This must be confirmed and resolved in Task 1. Options:
- Set `strict: false` on the `shipsmooth-windows` entry so the marketplace
  entry is the sole authority (no merge with `plugin.json`).
- Remove the `SessionStart` hook from `plugin.json` and declare it only in
  `marketplace.json` for both entries.

### Why not a single-entry fallback (e.g. `node || powershell`)?

Shell-form `||` fallback in `hooks.json` is unreliable: the failed `node`
invocation produces stderr that surfaces as a hook error to the user before
the fallback runs. There is no clean way to suppress it.

### Why not require users to install Node.js?

Silent failure with no actionable error is a poor user experience. The plugin
should work out of the box on a fresh Windows machine.

## Backlog issue

PB-52 (windows release support) — this plan delivers the missing piece: a
working `SessionStart` hook on Windows without system Node.js.

## Tasks

### Task 1: Finalise approach and resolve open questions [Medium]

One confirmed answer and two remaining open questions:

**Confirmed:** The marketplace schema has no `platform` field. The approach is
two named entries (`shipsmooth` + `shipsmooth-windows`) in `marketplace.json`,
both pointing to the same repo ref. `-ExecutionPolicy Bypass` is included in
the hook command.

**Remaining questions to resolve before writing code:**

1. **Hooks merge under `strict: true`** — does the `hooks` field in a
   marketplace entry *replace* or *merge with* the hooks declared in
   `plugin.json`? If it merges, both the `node` and `powershell` `SessionStart`
   hooks would fire on Windows. Confirm via the Claude Code source or a test
   install, then choose one of:
   - Set `strict: false` on the `shipsmooth-windows` entry (marketplace entry
     is sole authority, no merge).
   - Move `SessionStart` out of `plugin.json` entirely and declare it only in
     `marketplace.json` for both entries.

2. **`session-start.ps1` location** — the marketplace `hooks` override uses
   `${CLAUDE_PLUGIN_ROOT}/dist/session-start.ps1`. Confirm that
   `${CLAUDE_PLUGIN_ROOT}` resolves correctly for a marketplace-installed
   plugin (vs a locally-sourced plugin), and that `dist/` is included in the
   files Claude Code copies to its plugin cache when installing from a GitHub
   source.

Deliverable: a short decision record (added to this plan file as an addendum)
confirming the resolved answers, so Tasks 2–5 can proceed without ambiguity.

### Task 2: Lint session-start.ps1 for PowerShell 5.1 compatibility [Low]

*Depends-on: 1*

The script must run on PowerShell 5.1 (the version inbox on Windows 10/11).
To catch accidental use of PS 6+ / PS 7+ features without needing a Windows
machine, use PSScriptAnalyzer's compatibility rules on Linux:

- Install PowerShell 7.1+ on the Linux CI/dev machine
  (`sudo apt-get install -y powershell` or the equivalent snap/tarball install).
- Install PSScriptAnalyzer: `Install-Module -Name PSScriptAnalyzer -Force`.
- Run the compatibility check against the `PSv5_1` ruleset:
  ```powershell
  Invoke-ScriptAnalyzer -Path session-start.ps1 \
    -Settings PSScriptAnalyzerSettings.psd1
  ```
  where `PSScriptAnalyzerSettings.psd1` pins
  `CompatibilityProfilePath = 'win-8_x64_10.0.14393.0_5.1.14393.206'`
  (see https://devblogs.microsoft.com/powershell/using-psscriptanalyzer-to-check-powershell-version-compatibility/).
- Add this check as a step in the GitHub Actions workflow (Task 4) so it runs
  on every PR.

If PSScriptAnalyzer flags any incompatibility, fix the script before
proceeding. Document any intentional PS 5.1 limitations (e.g. `Expand-Archive`
requires PS 5.0+, which is fine; `-FollowRelLink` on `Invoke-WebRequest`
requires PS 6+, which must be avoided).

### Task 3: Write session-start.ps1 [Low]

*Depends-on: 1,2*

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

### Task 4: Update marketplace.json with shipsmooth-windows entry [Low]

*Depends-on: 3*

Add the `shipsmooth-windows` plugin entry to
`plugin-resources/src/main/resources/claude-plugin/marketplace.json` (the
source-of-truth file that gets filtered by Maven into the build output).

The entry must:
- Point to the same `source` repo and ref as the existing `shipsmooth` entry
  (ref is interpolated from `${project.version}` at build time, same as now).
- Override `hooks` with the PowerShell `SessionStart` command (exact form
  confirmed in Task 1, including `strict` setting).
- Include a `description` that clearly explains it is the Windows variant and
  when to use it.

No new Maven profile is needed. No separate build output directory. The
existing `prod` build produces the same `marketplace.json` with both entries.

### Task 5: CI verification [Low]

*Depends-on: 4*

Update the GitHub Actions release workflow to verify:

- `build/.claude-plugin/marketplace.json` contains a `shipsmooth-windows`
  entry (grep check).
- The `shipsmooth-windows` hooks command string contains
  `session-start.ps1` (grep check).
- `build/dist/session-start.ps1` exists in the built output.
- PSScriptAnalyzer PS 5.1 compatibility check passes (install PowerShell +
  PSScriptAnalyzer in the CI job, run `Invoke-ScriptAnalyzer`).
