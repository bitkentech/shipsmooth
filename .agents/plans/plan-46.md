# Plan 46: Cross-Platform Runtime Install in Pure TypeScript

## Context

`session-start.ts` (the plugin's SessionStart hook) downloads and installs the
`shipsmooth-tasks` jlink runtime on first use. The current implementation has three
cross-platform liabilities:

1. **Shells out to `curl`** for the download. Available on macOS 10.15+ and Windows 10
   1803+, but not guaranteed on minimal Linux containers (e.g. distroless, busybox).
2. **Shells out to `unzip`** for extraction. **Not present on Windows by default** and
   not guaranteed on minimal Linux images. This is the hard blocker for Windows support.
3. **Does not `chmod 0o755` the binary** after extracting the downloaded zip. Works today
   because the release zip preserves Unix mode bits, but is fragile — any release built
   with a Windows-aware zipper that drops mode bits would silently break the binary.

In addition, the install path has weak diagnostics:

- No log before download begins. If the network hangs, the user sees nothing.
- `curl` failure messages don't include the URL.
- No retry on transient network failures.
- Unsupported-platform error doesn't list which platforms are supported.

Windows support is out of scope for this plan — no Windows release artifact exists yet.
But the install code should stop *blocking* Windows: once a Windows artifact is published
and `win32-x64` is added to `supportedPlatforms`, the installer should just work.

Backlog issue: none yet (tracked inline here as feature context).

## Design

### Replace external commands with Node built-ins + one dep

| External command | Replacement | New dep? |
|---|---|---|
| `curl -fsSL` | Node `https.get` with manual redirect following | No |
| `unzip` | `adm-zip` npm package (synchronous, preserves Unix mode bits) | Yes — `adm-zip` |

`adm-zip` is the right choice over `yauzl`/`unzipper`:
- Zero transitive dependencies (~50 KB).
- Synchronous API matches the existing synchronous flow in `installRuntime`.
- Preserves Unix mode bits on extraction (the `bin/shipsmooth-tasks` executable bit
  survives without a manual `chmod`). We still chmod defensively.

The zip itself stays — it provides ~35% transfer-size savings and a single-stream
download, which is materially better than walking a manifest of hundreds of jlink files.

### Download function

```typescript
function downloadFile(url: string, dest: string): void {
  // synchronous-feeling but actually uses a worker-style block; or:
  // use child_process to run a node subprocess that does the async download.
}
```

Node has no synchronous HTTPS API. Two options:

1. **Use `child_process.execFileSync` on `node -e '...'`** to run an async download in a
   sub-process. Heavy and awkward.
2. **Restructure the call site to be async.** `installRuntime` becomes `async`; the CLI
   entrypoint awaits it. Tests remain straightforward because they use `node:test` which
   supports async test functions.

We choose option 2. Cost: callers in `hooks.json` already invoke this via
`node -e "require('./session-start').installRuntime(...)"` — needs to become
`require('./session-start').installRuntime(...).then(...)`. We will check the bootstrap
shape during task 1 and adjust if needed.

### Redirect handling

GitHub release URLs redirect to a CDN (`objects.githubusercontent.com`). The download
function must follow 3xx responses (301, 302, 303, 307, 308) up to a small limit (5 hops).
Standard pattern, ~15 lines.

### Logging

All progress logs go to **stderr** (hooks may parse stdout). Add:

- Before download: `shipsmooth: downloading runtime {version} from {url}`
- On retry: `shipsmooth: download attempt {n} failed: {reason}; retrying`
- After extract: existing `installed at {runtimeDir}` line stays
- On fast path (already cached): no log (unchanged)

### Retry

One retry on download failure with a short fixed delay (e.g. 1s). Two attempts total.
Distinguish "no retry" errors (4xx HTTP, unsupported platform) from "retry" errors
(network timeout, 5xx, DNS).

### Error message hardening

- Unsupported-platform error includes the list of supported platforms.
- Download error includes the URL and the underlying cause.
- chmod after extraction (defensive — adm-zip should preserve bits, but be sure).

### What does NOT change

- The zip release format and naming convention (`shipsmooth-tasks-{version}-{platform}.zip`).
- The on-disk cache layout (`{cacheDir}/runtime-{version}/bin/shipsmooth-tasks`).
- The `jlinkDir` (dev) branch — already pure Node, untouched.
- `resolveCache` and tilde expansion logic.

## Tasks

### Task 1: Add adm-zip dependency and audit hook entrypoint shape [Low]

Add `adm-zip` (latest 0.5.x) and `@types/adm-zip` to `plugin-node/package.json`. Run
`npm install` and commit the lockfile change. Inspect how `installRuntime` is invoked
from the SessionStart hook (search `hooks.json` and any JTE template that emits the
bootstrap `node -e` command) to determine whether making `installRuntime` async breaks
the hook contract. If the bootstrap is synchronous, adjust it to await/`.then()` the
returned promise. Document the bootstrap shape in a comment at the top of session-start.ts.

### Task 2: Replace curl with Node https + redirect following [Medium]

Make `installRuntime` and `downloadAndInstall` async. Replace `downloadFile`'s
`spawnSync('curl', ...)` with a Node `https.get` implementation that:

- Follows 3xx redirects up to 5 hops.
- Streams the response body to disk via `pipeline`.
- Sets a `User-Agent` header (`shipsmooth-runtime-installer/{version}`).
- Throws with the URL embedded in the message on failure.

Update unit tests to await async calls. Add a test that asserts `downloadFile` throws
when the URL returns 404 (use a local http server fixture or a known-bad GitHub URL).

### Task 3: Replace unzip with adm-zip [Medium]

Replace `execFileSync('unzip', ...)` in `downloadAndInstall` with `adm-zip`'s
synchronous extract API. Keep the `extractDir.tmp` → `renameSync` atomic-rename
pattern. Add a defensive `chmod 0o755` on the extracted binary path after extraction.

Add a unit test that:
- Programmatically builds a small zip (using adm-zip) containing
  `bin/shipsmooth-tasks` as an executable shell script.
- Serves it from a local http server.
- Calls `installRuntime` against that URL (requires the URL to be overridable — see
  task 4 below if needed, or inject via env var for the test).
- Asserts the extracted binary is executable.

### Task 4: Add logging and one-retry on download failure [Low]

Add stderr log lines for "downloading from URL" and "retrying after failure". Wrap
`downloadFile` in a retry loop: max 2 attempts, 1s delay between, no retry on 4xx
or unsupported-platform errors. Add a test that asserts the retry path is taken for
a 5xx response (using the local http server fixture from task 3).

### Task 5: Improve unsupported-platform error message [Low]

When `forcePlatform` or the detected platform is not in `supportedPlatforms`, throw
an error message that lists the supported platforms explicitly (e.g.
`platform win32-x64 is not yet supported (supported: linux-x64, darwin-x64, darwin-arm64)`).
Update the existing `unsupported platform` test to assert the message contains the
list of supported platforms.

### Task 6: Integration test — full download path with local http server [Medium]

Add an integration test that spins up an http server on `127.0.0.1:0`, has it return
a programmatically-built jlink-shaped zip (`bin/shipsmooth-tasks` + a few dummy `lib/`
files), and exercises the full `installRuntime` download path end-to-end. Asserts the
runtime directory exists, the binary is executable, and the temp dir is cleaned up.
