# Plan 52: Windows (win32-x64) release support

## Context

shipsmooth currently ships runtime zips for three platforms: `linux-x64`, `darwin-x64`,
and `darwin-arm64`. Windows (`win32-x64`) users who install the plugin get an immediate
hard error from `session-start.ts`:

```
shipsmooth: platform win32-x64 is not yet supported (supported: linux-x64, darwin-x64, darwin-arm64)
```

A Windows JDK (IBM Semeru OpenJ9 25.0.2+10) is available at
`/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10` and includes `openj9.sharedclasses`,
so the same jlink cross-compile approach used for macOS can be applied for Windows.

The work spans four layers:

1. **jlink cross-compile** — add a `jlink-build-windows-x64` execution in
   `plugin-tasks-java/pom.xml` that produces `target/jlink-image-windows-x64`.
2. **PackageRuntime — Windows launcher** — the current launcher is a POSIX shell script.
   Windows needs a `.cmd` launcher inside `bin/`. The zip entry is
   `bin/shipsmooth-tasks.cmd` instead of `bin/shipsmooth-tasks`.
3. **PublishRelease** — add `windowsX64JdkHome` parameter; produce and upload the
   `windows-x64` zip alongside the existing three.
4. **session-start.ts** — add `win32-x64` to `supportedPlatforms`; fix the bin-path
   check for Windows (the binary is `bin/shipsmooth-tasks.cmd`); guard `chmod` calls so
   they only run on non-Windows.

### Windows launcher design

A `.cmd` launcher uses `%~dp0` to resolve its own directory, matching the POSIX
`dirname "$0"` pattern:

```cmd
@echo off
set "DIR=%~dp0"
set "INSTALL=%DIR%.."
set "SCC_DIR=%USERPROFILE%\.cache\shipsmooth\scc"
if not exist "%SCC_DIR%" mkdir "%SCC_DIR%"
"%INSTALL%\runtime\bin\java.exe" ^
  -Xquickstart ^
  -Xshareclasses:name=shipsmooth_v{VERSION},cacheDir="%SCC_DIR%",nonfatal ^
  -m io.bitken.shipsmooth.tasks/io.bitken.shipsmooth.tasks.TasksCli %*
```

`%USERPROFILE%\.cache` is the closest Windows equivalent to `$XDG_CACHE_HOME`.

### session-start.ts: bin path on Windows

On Windows the zip contains `bin/shipsmooth-tasks.cmd`, not `bin/shipsmooth-tasks`.
The existing `isExecutable` check via `fs.accessSync(p, fs.constants.X_OK)` will always
return false for a `.cmd` file on Windows (no POSIX execute bits). The fix:

```ts
function runtimeBin(runtimeDir: string, platform: string): string {
  return path.join(runtimeDir, 'bin',
    platform.startsWith('win32') ? 'shipsmooth-tasks.cmd' : 'shipsmooth-tasks');
}
```

`isExecutable` should check file existence on Windows instead of the X_OK bit.
`chmod` calls in `downloadAndInstall` and `installRuntime` must be skipped on Windows.

### pom.xml property

Add `<jlink.jmods.windows-x64>/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10</jlink.jmods.windows-x64>`
to `plugin-tasks-java/pom.xml` properties, matching the pattern used for darwin targets.

## Tasks

### Task 1: Cross-compile windows-x64 jlink image in plugin-tasks-java [Medium]

- Add `<jlink.jmods.windows-x64>/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10</jlink.jmods.windows-x64>` to the `<properties>` block in `plugin-tasks-java/pom.xml`.
- Add a `jlink-build-windows-x64` execution (phase `package`, inside the `jlink` profile) that runs jlink with `--module-path ${jlink.runtime.module.path}:${jlink.jmods.windows-x64}/jmods` and `--output ${project.build.directory}/jlink-image-windows-x64`. Same flags as `jlink-build-darwin-x64`: same modules, no header files, no man pages, `zip-9`.
- Add a `jlink-copy-windows-x64` execution that copies `jlink-image-windows-x64` to its platform-named dir, mirroring `jlink-copy-linux-x64`.
- Verify: `mvn package -pl plugin-tasks-java -am -Pjlink -Dexperimental.enabled=false` produces `plugin-tasks-java/target/jlink-image-windows-x64/bin/java.exe`.

### Task 2: Add Windows .cmd launcher to PackageRuntime [Medium]

- In `PackageRuntime.java`, add `buildWindowsLauncher()` returning the `.cmd` script content from the plan design.
- In `run()`, when `target` starts with `win32`, write `bin/shipsmooth-tasks.cmd` instead of `bin/shipsmooth-tasks`. No `setUnixMode` needed for the `.cmd` entry.
- Update `PackageRuntimeTest` with a `windows-x64` case: assert the zip contains `bin/shipsmooth-tasks.cmd` (not `bin/shipsmooth-tasks`) and that the launcher content contains `shipsmooth_v`.

### Task 3: Wire windows-x64 into PublishRelease [Medium]

*Depends-on: 2*

- Add `windowsX64JdkHome` field and constructor parameter to `PublishRelease`, alongside the existing three JDK homes.
- In `main()`, read `-Djdk.semeru.windows-x64` with default `/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10`.
- In `buildAndPackage()`, add a `PackageRuntime("windows-x64", ...)` call producing the `windows-x64.zip`.
- In `syncDistAndPublish()`, include the `windows-x64.zip` in the `gh release upload` call.
- Update `PublishReleaseTest` to pass a `windowsX64JdkHome` wherever `PublishRelease` is constructed.

### Task 4: Support win32-x64 in session-start.ts [Low]

- Add `win32-x64` to the `supportedPlatforms` array.
- Extract `runtimeBin(runtimeDir, platform)` helper returning `.cmd` path on win32.
- Replace the hardcoded `bin/shipsmooth-tasks` path in `installRuntime` and `downloadAndInstall` with `runtimeBin(...)`.
- In `downloadAndInstall`, wrap all `chmodSync` calls in `if (process.platform !== 'win32')` guards.
- In `installRuntime`, same guard on the post-`cpSync` chmod.
- Update `isExecutable` to check file existence (via `fs.existsSync`) on Windows instead of `X_OK`.
- Update `session-start.test.ts`: test that `win32-x64` passes the supported-platform check, and that `runtimeBin` returns the `.cmd` path for `win32-x64`.
