# Plan 55: Automate Windows release process

## Context

Plan 54 proved the offline JRE bundling approach via a manual smoke test.
`bitkentech/shipsmooth-windows` was assembled by hand and successfully
installed on a Windows machine via `/plugin install shipsmooth-windows@bitkentech`.
The `cmd.exe` xcopy hook, bundled JRE, and `SKILL.md` all work correctly.

This plan automates that manual process so that Windows releases are produced
by the same Maven build pipeline as the existing Linux/macOS releases.

## Deployment Model

The `bitkentech/shipsmooth-windows` GitHub repository is a **deployment target,
not a development repo**. To prevent Git binary history bloat (~79 MB JRE per
commit), each release is pushed as a fresh orphan commit with `--force`,
replacing the entire history. Only the latest release is installable; old
releases are not retained on the remote but are reconstructable from the
`shipsmooth` main repo's build history.

## Tasks

### Task 1: Implement minimal JLink build for Windows and update hooks.json [High]
* **Status:** `pending`
* **Details:** Add a Windows-specific jlink profile to `plugin-tasks-java/`
  that produces a stripped `win32-x64` image targeting under 35 MB uncompressed.
  Update `ResourceBuilder.java` to emit a Windows-variant `hooks.json` containing
  the native `cmd.exe` xcopy loop targeting
  `%LOCALAPPDATA%\shipsmooth\<version>\runtime`.

### Task 2: Adapt BuildProfile and PluginModel for Windows paths [Low]
* **Status:** `pending`
* **Details:** Add a `isWindows()` predicate to `BuildProfile.java`. Override
  `cliBin()` for the Windows profile to return the hardcoded stable path
  `%LOCALAPPDATA%\shipsmooth\<version>\runtime\bin\shipsmooth-tasks.bat` instead
  of the XDG cache expression used on Unix. Update `PluginModel.java` and the
  SKILL.jte template accordingly.
*Depends-on: 1*

### Task 3: Remove vestigial package.json from shipsmooth-windows repo [Low]
* **Status:** `pending`
* **Details:** Delete `package.json` from `bitkentech/shipsmooth-windows`. It
  was copied from the non-Windows build where it declares `fast-xml-parser` and
  `typescript` dependencies for Node.js scripts in `dist/`. The Windows plugin
  has no `dist/` directory and no Node.js scripts — the hook is a raw `cmd.exe`
  command and the runtime is the bundled JRE. The file serves no purpose and
  must not be included in future releases.

### Task 4: Automate orphan-push release to shipsmooth-windows repo [Low]
* **Status:** `pending`
* **Details:** Extend `PublishRelease.java` with a Windows release step that:
  (1) assembles the plugin payload (runtime/, hooks/, skills/, .claude-plugin/)
  into a staging directory, (2) checks out `bitkentech/shipsmooth-windows`,
  (3) creates a fresh orphan commit containing only the new artifacts, and
  (4) force-pushes to `main`. No history is retained on the remote — each
  release replaces the previous single commit.
*Depends-on: 1,2,3*

### Task 5: End-to-end release dry-run [Medium]
* **Status:** `pending`
* **Details:** Run the full automated pipeline locally for a test version.
  Verify the orphan push produces a single-commit `main` in
  `bitkentech/shipsmooth-windows`. Install the plugin on Windows and confirm
  the hook, JRE, and `shipsmooth-tasks.bat` all work correctly.
*Depends-on: 4*
