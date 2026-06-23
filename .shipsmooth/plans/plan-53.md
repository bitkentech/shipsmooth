# Plan 53: Windows cache path correctness

## Context

Plan 52 added `win32-x64` support but left two inconsistencies in how the
runtime cache directory is resolved on Windows:

1. **`resolveCache` in `session-start.ts`** falls back to
   `path.join(os.homedir(), '.cache')` when `XDG_CACHE_HOME` is unset.
   On Windows `os.homedir()` returns `C:\Users\<name>`, so the cache lands at
   `C:\Users\<name>\.cache\shipsmooth`. This is non-standard on Windows —
   the conventional location for user-scoped caches is `%LOCALAPPDATA%`
   (`C:\Users\<name>\AppData\Local`).

2. **The `.cmd` launcher** (built by `PackageRuntime.buildWindowsLauncher()`)
   hardcodes `%USERPROFILE%\.cache\shipsmooth\scc` as the OpenJ9 SCC
   directory. This must exactly match the parent of the `runtime-{version}`
   directory that `session-start.ts` installs into — otherwise the SCC sits
   outside the managed cache tree.

3. **`expandHome`** expands `~/` using `os.homedir()`. On Windows that prefix
   is not conventional; any `cacheDir` config value starting with `~/` would
   expand to `C:\Users\<name>\.cache\...` which may surprise Windows users.
   This is low-priority but worth documenting.

## Decision

Use `%LOCALAPPDATA%\shipsmooth` as the Windows cache root:

- In `session-start.ts`: when `process.platform === 'win32'` (and no explicit
  `cacheDir` config), resolve `process.env['LOCALAPPDATA']` and fall back to
  `path.join(os.homedir(), 'AppData', 'Local')` if the env var is absent.
- In `PackageRuntime.buildWindowsLauncher()`: replace
  `%USERPROFILE%\.cache\shipsmooth\scc` with
  `%LOCALAPPDATA%\shipsmooth\scc`.

Both paths then share the same root (`%LOCALAPPDATA%\shipsmooth`) and stay
consistent whether the user has `LOCALAPPDATA` set or not.

### Why not `%APPDATA%` (roaming)?

Roaming profiles sync across machines. A JVM shared-class cache is
machine-specific compiled code — syncing it would be wasteful or harmful.
`%LOCALAPPDATA%` (non-roaming) is the right choice.

### Why not `%USERPROFILE%\.cache`?

That path works but is a Linux/XDG convention grafted onto Windows. It is not
where Windows users or tools expect to find application caches. `%LOCALAPPDATA%`
is where browsers, IDEs, and package managers put caches on Windows.

## Backlog issue

PB-52 (windows release support) — this plan is a follow-on correctness fix.

## Tasks

### Task 1: Fix resolveCache for Windows in session-start.ts [Low]

- In `resolveCache`, add a Windows branch: when `process.platform === 'win32'`
  and no explicit `cacheDir` is configured, return
  `path.join(process.env['LOCALAPPDATA'] ?? path.join(os.homedir(), 'AppData', 'Local'), 'shipsmooth')`.
- Keep the existing XDG/homedir logic for non-Windows.
- Update `session-start.test.ts`: add a test that on a simulated win32
  environment with `LOCALAPPDATA` set, `resolveCache({})` returns
  `%LOCALAPPDATA%\shipsmooth`. Add a test for the fallback when `LOCALAPPDATA`
  is absent.

### Task 2: Fix SCC dir in Windows .cmd launcher [Low]

*Depends-on: 1*

- In `PackageRuntime.buildWindowsLauncher()`, change the SCC dir line from
  `%USERPROFILE%\.cache\shipsmooth\scc` to `%LOCALAPPDATA%\shipsmooth\scc`.
- Update `PackageRuntimeTest.windowsLauncherContainsVersion` (or add a new
  test) to assert the launcher content contains `%LOCALAPPDATA%` and does not
  contain `%USERPROFILE%`.