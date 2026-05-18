# Proposal: Install shipsmooth-tasks launcher to $HOME/.local/bin

## Background

The XDG Base Directory spec designates `$HOME/.local/bin` for user-specific executables.
Distributions are expected to include it in `$PATH`. Currently the `shipsmooth-tasks`
launcher lives only inside `$XDG_CACHE_HOME/shipsmooth/runtime-{version}/bin/`, which
means users must know the full cache path or rely on the SKILL.md-embedded absolute path.

## Proposed change

After `installRuntime()` places the runtime under `cacheDir/runtime-{version}/`, also
write a symlink:

```
$HOME/.local/bin/shipsmooth-tasks -> $HOME/.cache/shipsmooth/runtime-{version}/bin/shipsmooth-tasks
```

Changes required in `session-start.ts`:
1. `fs.mkdirSync(path.join(os.homedir(), '.local/bin'), { recursive: true })`
2. Write/replace the symlink after every successful install (both the `cpSync` and
   `downloadAndInstall` paths). Use `fs.symlinkSync` with a preceding `fs.rmSync` if it
   already exists, so version upgrades update the symlink atomically.
3. The existing early-return (`if (isExecutable(bin)) return`) stays correct — a new
   plugin version means a new `runtimeDir`, so `isExecutable` returns false and the
   symlink gets updated automatically.

No change needed to `PackageRuntime`, `PublishRelease`, or the launcher script itself.

## Scope

~10 lines in `session-start.ts` + one test case. No perf impact — symlink resolution
is a single kernel `stat` call, unmeasurably faster than JVM startup.

## What stays in $XDG_CACHE_HOME

The full runtime tree (JVM, jars, lib/) and the SCC remain under
`$XDG_CACHE_HOME/shipsmooth/` — correct per the spec since they are re-downloadable
non-essential files.

## Note on shared home directories

The XDG spec cautions against placing architecture-specific compiled binaries in
`~/.local/bin` on NFS-shared homes. The `shipsmooth-tasks` launcher is a plain shell
script, so this concern does not apply. The JVM itself stays in the cache, not in
`~/.local/bin`.