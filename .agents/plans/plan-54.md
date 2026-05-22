# Plan 54: Windows plugin without system Node.js

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

## Proposed New Architecture (The Main Approach)

To ensure an out-of-the-box experience on Windows, we are implementing a
**Build-Time Platform Split** resulting in a dedicated `shipsmooth-windows`
entry.

Instead of executing an online bootstrap download script on the client machine,
`shipsmooth-windows` will **bundle a pre-stripped win32-x64 jlink image
(compressed to ~25–35 MB) directly within the plugin's distribution payload**
published to the marketplace registry.

To bypass Claude Code's known bug where `${CLAUDE_PLUGIN_ROOT}` fails to
resolve inside strings on Windows (GitHub issue #59713), the `SessionStart`
hook will execute a native `cmd.exe` wildcard directory loop. This loop
dynamically scans Claude Code's internal plugin cache directory, matches any
active version string, and performs an offline `xcopy` to a stable,
non-volatile local application directory: `%LOCALAPPDATA%\.shipsmooth\<version>\runtime`.

This approach provides several critical advantages:
1. **GPO Compliance:** It uses native command processor utilities (`FOR /D`,
   `IF EXIST`, `xcopy`), which are completely immune to corporate Group Policy
   execution restrictions.
2. **Robust Offline Support:** Because the JRE arrives packaged inside the core
   plugin payload, it avoids network proxy/firewall friction (e.g., deep packet
   inspection) that typically breaks arbitrary client-side `curl` operations
   out to GitHub releases.
3. **Static Execution Path:** It mirrors the volatile, version-dependent cache
   paths into a predictable local layout. This allows the tools declared in
   `plugin.json` to safely target
   `%LOCALAPPDATA%\.shipsmooth\<version>\runtime\bin\java.exe` across future plugin
   updates.

## Summary of Tried and Discarded Approaches

During the course of de-risking this plan, multiple candidate solutions were
investigated and systematically eliminated:

### 1. PowerShell 5.1 Bootstrapper (`-ExecutionPolicy Bypass`)
* **Concept:** Run a `session-start.ps1` script via the inbox PowerShell
  runtime to handle the HTTP download and zip extraction.
* **Why Discarded:** Smoke testing revealed that on enterprise/corporate
  managed Windows machines (the primary user demographic), IT administrators
  commonly enforce script block execution policies via Group Policy (GPO).
  When a GPO restriction is active, the `-ExecutionPolicy Bypass` flag is
  silently ignored by the operating system, causing the script to fail.
  Resolving this would require a paid commercial EV Code Signing certificate
  (~$300–$500/year) and significant CI re-signing pipeline overhead.

### 2. NPM-Registry Bundling Distribution
### (`@pramodbiligiri/shipsmooth-windows-smoke`)
* **Concept:** Package the entire 49 MB `jlink` image inside a standard npm
  package registry tarball and let Claude Code fetch and extract it natively
  through its npm source type installer.
* **Why Discarded:** End-to-end testing demonstrated a circular dependency.
  Claude Code’s internal npm installer engine attempts to shell out to the
  system's host `npm` binary. On a clean Windows developer machine
  without Node.js installed, this throws a fatal exception: `Command 'npm' not
  found or is in an unsafe location`.

### 3. Native Bootstrapper Binary (Go / Rust)
* **Concept:** Compile a small standalone executable to carry out the
  download/unzipping steps.
* **Why Discarded:** While it solves the GPO execution policy problem, it
  introduces repo bloat, cross-compilation pipeline complexities, and potential
  Windows SmartScreen warnings on corporate systems if left unsigned. The
  local `cmd.exe` bundle-and-copy loop achieves the same results natively with
  zero extra binaries.

## Tasks

### Task 6: Implement minimal JLink build and update hooks.json [High]
* **Status:** `pending`
* **Details:** Optimize the `jlink` assembly pipeline configuration in
  `plugin-tasks-java/` to strip all non-essential modules, targeting an
  uncompressed footprint under 35 MB. Update `hooks/hooks.json` in the
  `shipsmooth-windows` build branch to use the native `cmd.exe` directory
  wildcard copy statement targeting `%LOCALAPPDATA%\.shipsmooth\<version>\runtime`.

### Task 7: Adapt plugin.json and template model paths [Low]
* **Status:** `pending`
* **Details:** Update the tool execution strings declared in `plugin.json` for
  the Windows variant to point to the static `%LOCALAPPDATA%\.shipsmooth\<version>\runtime\bin\java.exe`
  copy location. Adjust `PluginModel.java` and `BuildProfile.java` to match this behavior.

### Task 8: Manual smoke test of the Windows bundling approach [High]
* **Status:** `pending`
* **Details:** Manually assemble a `shipsmooth-windows` build containing the bundled JRE. Push this to a temporary "smoke-test" GitHub repository. Perform a `/plugin install <repo-url>` on a Windows machine. Verify the `cmd.exe` hook correctly mirrors the JRE to `%LOCALAPPDATA%\.shipsmooth\<version>\runtime` and that `shipsmooth-tasks` runs without a system Node.js.
*Depends-on: 6,7*

### Task 1: Smoke-test the Windows wiring end-to-end [Medium]
* **Status:** `agent-coded`
* **Details:** Evaluated basic PowerShell invocation hooks. Hook fired
  correctly, but surfaced the critical corporate GPO blocking restriction.

### Task 2: De-risk npm bundling approach [Medium]
* **Status:** `agent-coded`
* **Details:** Published smoke test package to npmjs.com. Succeeded on Unix
  platforms but verified a fatal circular dependency on Windows due to the lack
  of system `npm`.

### Task 9: Final release to downstream `shipsmooth-windows` repository [Low]
* **Status:** `pending`
* **Details:** Once the manual smoke test passes, perform the final push to the official `bitkentech/shipsmooth-windows` repository. Verify the CI/CD pipeline correctly handles the bundled assets.
*Depends-on: 8*

### Task 3: Write session-start.ps1 [Low]
* **Status:** `Canceled`
* **Reason:** PowerShell strategy abandoned due to corporate GPO execution
  policy constraints.

### Task 4: Update marketplace.json with shipsmooth-windows entry [Low]
* **Status:** `Canceled`
* **Reason:** This task was coupled directly to the deployment architecture of
  the obsolete PowerShell script asset track.

### Task 5: CI verification [Low]
* **Status:** `Canceled`
* **Reason:** Canceled to clear the baseline for the rewritten bundling
  execution validation pipeline.
