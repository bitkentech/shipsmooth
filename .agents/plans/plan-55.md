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

## Windows Repo Location

The `shipsmooth-windows` repo is expected to be a sibling of the `shipsmooth`
repo on the local filesystem (i.e. `../shipsmooth-windows` relative to the repo
root). This default is computed at runtime as `repoRoot.getParent().resolve("shipsmooth-windows")`.

It can be overridden via a Maven system property:
```
-Dshipsmooth.windows.repo=/path/to/shipsmooth-windows
```

This property is documented in `PublishRelease.java` alongside the existing JDK
path properties (`jdk.semeru.linux-x64`, etc.) and follows the same convention.

## Tasks

### Task 1: Wire ResourceBuilder to emit a Windows-variant hooks.json [High]
* **Status:** `pending`
* **Details:** The `plugin-tasks-java/pom.xml` already cross-compiles a
  `win32-x64` jlink image (id `jlink-build-windows-x64`). The remaining work
  is in `ResourceBuilder.java`: when the build profile is Windows, emit a
  `hooks.json` containing the native `cmd.exe` xcopy loop targeting
  `%LOCALAPPDATA%\shipsmooth\<version>\runtime` instead of the Node.js
  `session-start.js` command used on Unix.

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
  (1) resolves the `shipsmooth-windows` repo path — defaulting to
  `repoRoot.getParent().resolve("shipsmooth-windows")`, overridable via
  `-Dshipsmooth.windows.repo=<path>` — (2) assembles the plugin payload
  (`runtime/`, `hooks/`, `skills/`, `.claude-plugin/`) into that directory,
  (3) creates a fresh orphan commit containing only the new artifacts, and
  (4) force-pushes to `main`. No history is retained on the remote — each
  release replaces the previous single commit. Document the property alongside
  the existing JDK path properties in the `main()` usage string.
*Depends-on: 1,2,3*

### Task 5: End-to-end release dry-run [Medium]
* **Status:** `pending`
* **Details:** Run the full automated pipeline locally for a test version.
  Verify the orphan push produces a single-commit `main` in
  `bitkentech/shipsmooth-windows`. Install the plugin on Windows and confirm
  the hook, JRE, and `shipsmooth-tasks.bat` all work correctly.
*Depends-on: 4*

### Task 6: Update DEVELOPMENT.md to document the Windows release process [Low]
* **Status:** `pending`
* **Details:** Update `DEVELOPMENT.md` to cover the Windows release workflow:
  the `shipsmooth-windows` sibling repo requirement, the orphan-push model,
  the `-Dshipsmooth.windows.repo` override property, and the install/verify
  steps. Should sit alongside the existing Linux/macOS release documentation.
*Depends-on: 5*
