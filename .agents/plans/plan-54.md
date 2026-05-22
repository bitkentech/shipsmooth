# Plan 54: Windows plugin smoke test — offline JRE bundling

## Context

Plans 52 and 53 added `win32-x64` runtime support and fixed the Windows cache
path. However, the `SessionStart` hook that installs the Java runtime
was originally wired to run via `node`. This requires `node` to be on
`PATH`.

On Windows, Claude Code ships its own bundled Node.js for internal use but does
not expose it on the system `PATH`. Users without a separately installed
Node.js get a silent hook failure on every session start — the Java runtime is
never downloaded and the plugin is effectively broken. The plugin must
work out of the box on a fresh Windows machine without making system Node.js a
prerequisite.

## Architecture: Offline JRE Bundling

To ensure an out-of-the-box experience on Windows, we implemented a
**Build-Time Platform Split** resulting in a dedicated `shipsmooth-windows`
entry in the plugin marketplace.

Instead of executing an online bootstrap download script on the client machine,
`shipsmooth-windows` **bundles a pre-stripped win32-x64 jlink image directly
within the plugin's distribution payload** published to the marketplace registry.

To bypass Claude Code's known bug where `${CLAUDE_PLUGIN_ROOT}` fails to
resolve inside strings on Windows (GitHub issue #59713), the `SessionStart`
hook executes a native `cmd.exe` wildcard directory loop. This loop
dynamically scans Claude Code's internal plugin cache directory, matches any
active version string, and performs an offline `xcopy` to a stable,
non-volatile local application directory: `%LOCALAPPDATA%\shipsmooth\<version>\runtime`.

This approach provides several critical advantages:
1. **GPO Compliance:** Uses native command processor utilities (`FOR /D`,
   `IF EXIST`, `xcopy`), completely immune to corporate Group Policy restrictions.
2. **Robust Offline Support:** The JRE arrives packaged inside the plugin
   payload, avoiding network proxy/firewall friction that typically breaks
   arbitrary client-side `curl` operations.
3. **Static Execution Path:** Mirrors the volatile, version-dependent cache
   paths into a predictable local layout, allowing tools in `plugin.json` to
   safely target `%LOCALAPPDATA%\shipsmooth\<version>\runtime\bin\java.exe`.

## Deployment Constraint: Latest Release Only

The `bitkentech/shipsmooth-windows` GitHub repository is a **deployment target,
not a development repo**. To prevent Git binary history bloat (each ~79 MB JRE
commit accumulates with every release), each release will be pushed as a fresh
orphan commit with `--force`, replacing the entire history with a single commit.

Consequence: only the latest release is installable via `/plugin install
shipsmooth-windows@bitkentech`. There is no version pinning. Old releases are
not retained in the remote repository but are reconstructable from the
`shipsmooth` main repo's build history. This is an acceptable tradeoff — the
Windows plugin version is tightly coupled to the bundled `shipsmooth-tasks`
runtime and there is no independent client-side versioning.

## Smoke Test Results

Plan 54 executed a manual end-to-end smoke test. Key findings:

- `/plugin install shipsmooth-windows@bitkentech` installs successfully on Windows
- The `cmd.exe` xcopy hook correctly mirrors the JRE to
  `%LOCALAPPDATA%\shipsmooth\0.3.10\runtime` on `SessionStart`
- `shipsmooth-tasks.bat` runs without a system Node.js
- Skill is invoked as `/shipsmooth-windows:start` (namespaced)
- `PowerShell(& "$env:LOCALAPPDATA\shipsmooth\0.3.10\runtime\bin\shipsmooth-tasks.bat" show --plan 1)` works correctly

### Lessons Learned from marketplace.json

- `owner` field is required
- `source` must be an object `{"source": "url", "url": "https://..."}` — GitHub
  shorthand strings are rejected, and `"."` is unsupported
- HTTPS URL required — the `github` shorthand source type defaults to SSH,
  breaking installs for users without SSH keys configured

## Summary of Tried and Discarded Approaches

### 1. PowerShell 5.1 Bootstrapper (`-ExecutionPolicy Bypass`)
* **Concept:** Run a `session-start.ps1` script via the inbox PowerShell
  runtime to handle the HTTP download and zip extraction.
* **Why Discarded:** On enterprise/corporate managed Windows machines, IT
  administrators commonly enforce script block execution policies via GPO.
  When a GPO restriction is active, the `-ExecutionPolicy Bypass` flag is
  silently ignored, causing the script to fail. Resolving this would require
  a paid commercial EV Code Signing certificate (~$300–$500/year).

### 2. NPM-Registry Bundling (`@pramodbiligiri/shipsmooth-windows-smoke`)
* **Concept:** Package the jlink image inside an npm tarball and let Claude
  Code fetch and extract it natively through its npm source type installer.
* **Why Discarded:** Claude Code's internal npm installer shells out to the
  system's host `npm` binary. On a clean Windows machine without Node.js,
  this throws a fatal exception: `Command 'npm' not found or is in an unsafe location`.

### 3. Native Bootstrapper Binary (Go / Rust)
* **Concept:** Compile a small standalone executable for the download/unzip steps.
* **Why Discarded:** Introduces repo bloat, cross-compilation complexity, and
  potential Windows SmartScreen warnings if unsigned. The `cmd.exe` bundle-and-copy
  loop achieves the same results natively with zero extra binaries.

## Tasks

### Task 1: Smoke-test the Windows wiring end-to-end [Medium]
* **Status:** `agent-coded`
* **Details:** Evaluated basic PowerShell invocation hooks. Hook fired
  correctly, but surfaced the critical corporate GPO blocking restriction.

### Task 2: De-risk npm bundling approach [Medium]
* **Status:** `agent-coded`
* **Details:** Published smoke test package to npmjs.com. Succeeded on Unix
  platforms but verified a fatal circular dependency on Windows due to the lack
  of system `npm`.

### Task 8: Manual smoke test of the Windows bundling approach [High]
* **Status:** `closed`
* **Details:** Manually assembled a `shipsmooth-windows` build containing the
  bundled JRE. Pushed to `bitkentech/shipsmooth-windows` on GitHub. Verified
  `/plugin install shipsmooth-windows@bitkentech` works end-to-end on Windows.
  The `cmd.exe` xcopy hook correctly mirrors the JRE to
  `%LOCALAPPDATA%\shipsmooth\0.3.10\runtime` and `shipsmooth-tasks.bat` runs
  without a system Node.js. See Smoke Test Results section above.

### Task 3: Write session-start.ps1 [Low]
* **Status:** `Canceled`
* **Reason:** PowerShell strategy abandoned due to corporate GPO execution
  policy constraints.

### Task 4: Update marketplace.json with shipsmooth-windows entry [Low]
* **Status:** `Canceled`
* **Reason:** Coupled to the obsolete PowerShell script asset track.

### Task 5: CI verification [Low]
* **Status:** `Canceled`
* **Reason:** Canceled to clear the baseline for the rewritten bundling pipeline.

## Moved to Plan 55

The following tasks were originally part of Plan 54 but have been moved to
Plan 55 (Automate Windows release process), which covers the full build
automation and release pipeline:

- Task 6: Implement minimal JLink build and update hooks.json
- Task 7: Adapt plugin.json and template model paths
- Task 9: Final release to downstream shipsmooth-windows repository
- Task 10: Remove vestigial package.json from shipsmooth-windows repo
