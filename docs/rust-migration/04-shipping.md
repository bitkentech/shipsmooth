# Shipping the Rust binary: dual-engine releases and cutover

Scope: the release, packaging, and installation mechanics needed to ship the Rust
CLI alongside the Java one, and the path to making Rust the default. Companion
files: [00-overview.md](00-overview.md) §"Risks and gotchas" flags this as the
follow-up to plan before cutting any release from the Rust tree;
[02-cli.md](02-cli.md) records that the CLI itself is feature-complete and
parity-verified. This file is the plan that note defers to.

Prerequisite state (plan-109, 2026-08-20): every `cli` package is ported, 45
parity scenarios byte-identical, nothing left to port. What remains is entirely a
distribution problem.

## Goal

**Ship both engines from one version line, with the user noticing nothing until
they opt in — and nothing at cutover either.**

Two properties drive every decision below.

### 1. End-user interaction does not change

Not during the parallel period, and not at cutover. The user keeps typing
`/shipsmooth:start`. Their agent config keeps naming the same plugin. The binary
is `shipsmooth` under both engines (`crates/ss-cli/Cargo.toml` already sets
`[[bin]] name = "shipsmooth"`), the skills are identical, the state tree is
byte-compatible in both directions (02-cli.md §definition of done), and the
install path they would see in `SKILL.md` reads the same.

The engine is an implementation detail behind one environment variable:

```sh
SHIPSMOOTH_ENGINE=rust      # opt in
SHIPSMOOTH_ENGINE=java      # explicit default (and the escape hatch after cutover)
# unset                     → java during the parallel period, rust after cutover
```

A user who never sets it gets Java today and, on cutover day, a binary that starts
in 10ms instead of 450ms — with no reinstall, no config edit, and no migration.
That is the whole point of the scheme: **cutover is a default flip, not an event
users participate in.**

This requires one discipline: **the engine must never be baked into a rendered
artifact.** If the hook command, `hooks.json`, or `SKILL.md`'s `SS="…"` line
encoded an engine, switching would need a rebuild and a re-install, and the
cutover would stop being a one-liner. The engine is therefore resolved *at run
time by the installer script*, and the rendered plugin stays engine-agnostic.

### 2. Both engines build consistently, every time

One version line, one release command, both engines in step. Concretely:

- **One version number.** `0.4.0` names both the Java and the Rust artifact. No
  prerelease channel, no second cadence, no independent Rust versioning to track.
- **One release invocation** produces both, from the same commit.
- **A Rust failure can never strand a Java release.** The Rust build is gated and
  sits in the safely-re-runnable part of the flow.
- **The two version sources cannot drift** — see §"Reconciling the two version
  sources", which is currently the single largest consistency defect.

## The two facts that make this cheap

**The zip contract is already engine-agnostic.** After unzipping,
`install-shipsmooth.sh` checks exactly one thing: that `bin/shipsmooth` exists and
is executable (the `EXTRACTED_BIN` guard). A Rust zip containing one static
`bin/shipsmooth` and nothing else satisfies it unchanged. No installer
restructure is needed — only a different asset name and a different cache
directory.

**`Env` is the precedent for an orthogonal naming dimension.** In `Target.java:25`
the cache directory is derived once, `platform.cacheSubdir(basePluginName, env)`
→ `Env.decorate` → append `-dev`. Engine is a second dimension of that same shape,
and is introduced the same way rather than as a special case.

## Naming conventions

### Release assets

Today `PackageRuntime` emits four zips named `shipsmooth-<ver>-<platform>.zip`,
uploaded by `PublishRelease.syncDistAndPublish`. Rust assets take an engine infix;
the existing names are left untouched:

```
shipsmooth-0.4.0-linux-x64.zip          Java  (default, name unchanged)
shipsmooth-0.4.0-darwin-x64.zip         Java
shipsmooth-0.4.0-darwin-arm64.zip       Java
shipsmooth-0.4.0-win32-x64.zip          Java  (Windows sibling repo)

shipsmooth-rs-0.4.0-linux-x64.zip       Rust  (opt-in)
shipsmooth-rs-0.4.0-darwin-x64.zip      Rust
shipsmooth-rs-0.4.0-darwin-arm64.zip    Rust
```

Keeping the Java names byte-identical means **every already-installed client keeps
working** — no shipped version's install path changes. At cutover the `-rs` infix
is dropped and the Java zips stop being published, so the default name resolves to
the Rust binary with zero client-side change.

Windows Rust is deliberately excluded at first (§Risks).

### Install location

```
~/.cache/shipsmooth/0.4.0/bin/shipsmooth         Java  (unchanged)
~/.cache/shipsmooth/0.4.0-rs/bin/shipsmooth      Rust
```

Using `<version>-rs` as the runtime-directory name, rather than adding a path
level, keeps the existing `RUNTIME_DIR` / `BIN` / `mktemp` / atomic-`mv` logic
exactly as it is — one string assignment, not a restructure. Both engines coexist,
each independently re-downloadable, and the `-x "$BIN"` early-exit stays correct
per engine. The dev suffix composes as it does today:
`~/.cache/shipsmooth-dev/0.4.0-rs/…`.

## Changes

### 1. `install-shipsmooth.sh` — the only place engine is resolved

`harness/shared/src/main/resources/install-shipsmooth.sh`, after the existing
`NAME` / `VERSION` argument parsing:

| Step | Behaviour |
|---|---|
| Read `SHIPSMOOTH_ENGINE` | Default `java`. Validate `java\|rust`; `die` on anything else — an unrecognised value must **not** silently fall back, or a typo becomes a mystifying "why is it still slow" |
| `rust` | `ASSET_STEM="$NAME-rs"`, `RUNTIME_VER="$VERSION-rs"` |
| `java` | `ASSET_STEM="$NAME"`, `RUNTIME_VER="$VERSION"` — byte-identical to today |
| Derive | `RUNTIME_DIR="$CACHE_DIR/$RUNTIME_VER"`, `URL="$URL_BASE/$ASSET_STEM-$VERSION-$PLATFORM.zip"` |
| Log | Print the selected engine. During a parallel period "which one am I running" is the most common support question; the answer belongs in hook output already |

Preserve `set -eu`, the `trap` / atomic-`mv` structure, and the strict-POSIX
stock-macOS toolset (`sh curl unzip uname chmod mktemp mv`). No bashisms.

Scope the header's OpenJ9 rationale to the Java case: the Rust zip has no
`runtime/lib/jspawnhelper`, so the stored-permissions argument is no longer a
claim about both engines.

**`Os.cliBinPath` stays unchanged**, and this is the load-bearing consequence.
The rendered read-back path (`plugin-model/…/Os.java:52`) keeps pointing at
`…/<version>/bin/shipsmooth` — the Java location — so under `rust` the installer
must make that path resolve to the Rust binary. It does so by additionally placing
a **symlink** at `$CACHE_DIR/$VERSION/bin/shipsmooth` →
`$CACHE_DIR/$VERSION-rs/bin/shipsmooth`.

That keeps `cliBinPath` and every rendered artifact byte-identical, so all of
`TargetIntegrationTest`'s exact-string assertions and the entire Windows path stay
as they are. It confines the feature to one shell script and makes the active
engine visible with `ls -l`.

The alternative — encoding the engine in the rendered shell expression — is
rejected. Plan-105 was a regression from exactly that kind of nested `${VAR:-…}`
expansion (`~` never expands inside `${VAR:-…}` in quotes), and `${VAR:+…}` cannot
map `rust`→`-rs` and `java`→empty on its own.

Keep the two documented siblings in sync — each already carries a "keep in sync"
comment pointing at the others:

- `harness/shared/scripts/tasks/session-start.ts` (`resolveCache`) — the Node
  bootstrap used by dev variants and OpenCode
- `plugin-model/src/main/java/io/bitken/ss/resources/Os.java:52`

### 2. Rust packaging — a separate packager, not a branch in `PackageRuntime`

**Do not extend `PackageRuntime`.** It emits an SCC-warming shell launcher
(`buildPosixLauncher`, `-Xshareclasses:name=shipsmooth_v<ver>`) wrapping a
`runtime/` jlink tree — all meaningless for a single static binary. Branching it
would tangle two unrelated artifact layouts in one class.

Add a small sibling packager that zips one file, `bin/shipsmooth`, mode 0755,
built with the `release-small` profile already defined in `exp/rust/Cargo.toml`
(opt-level `z`, LTO, `codegen-units = 1`, strip, panic abort — ~2.3 MB).

Wire the cross-compile as a new task in `exp/rust/build.gradle.kts`, reusing its
`RustToolchain` resolution (`-Pcargo.home` / `$CARGO_HOME` / `PATH`) and its
established `onlyIf { toolchain.found() }` skip-don't-fail convention.

### 3. `PublishRelease` — attach the Rust zips, behind a gate

`packaging/src/main/java/io/bitken/ss/dist/PublishRelease.java`.

- **Build and upload inside `buildAndPackage`** — *before* the
  `git checkout releases` / tag / push window. The flow's known dangerous window
  is after the tag and branch are pushed but before the GitHub Release exists; a
  Rust failure must land in the safely-re-runnable part.
- **Gate behind a property, default off**, mirroring `-PpublishOpencodeNpm`
  (`PUBLISH_OPENCODE_NPM_DEFAULT = false`). Plan-89 fixed precisely this failure
  class: npm auth failing mid-flow stranded the GitHub and Windows releases
  half-done. A missing Rust toolchain or a cross-compile failure must never
  strand a Java release.
- **`ReleaseGuard` is JVM-specific and must be skipped for Rust.** It disassembles
  baked `Build.class` constants out of each jlink image with `jimage`/`javap`, and
  `PublishRelease` hard-fails the entire release if it cannot run — so it must be
  skipped, not fed Rust artifacts. The Rust equivalent is stronger by
  construction (the `experimental` cargo feature is part of the build fingerprint,
  so plan-75's stale-constant defect class cannot recur), but the *release-time*
  check still needs a minimal form: exec the linux-x64 Rust binary's `--version`
  and assert it matches the release version.

### 4. Reconciling the two version sources

This is the largest consistency defect today, and it becomes user-visible the
moment Rust artifacts ship.

| Source | Value | Note |
|---|---|---|
| `gradle.properties` `plugin.version` | `0.3.36` | bumped by `PublishRelease.bumpVersionInGradleProperties` |
| `exp/rust/Cargo.toml` `[workspace.package] version` | `0.3.34` | commented "kept in lockstep" — currently two versions behind |
| `crates/ss-cli/src/main.rs` | `assert_eq!(env!("CARGO_PKG_VERSION"), "0.3.34")` | a hardcoded literal, so bumping Cargo breaks a test |

Two changes make "build both consistently every time" true rather than aspirational:

1. Extend `bumpAndCommitVersion` to rewrite the Cargo workspace version alongside
   the `gradle.properties` line, in the same commit.
2. Change that test to compare `CARGO_PKG_VERSION` against the workspace version
   rather than a baked literal, so it stops being a bump-blocker while still
   pinning the invariant it was written to protect.

### 5. Docs

Document the opt-in in `EXPERIMENTAL.md`, which already carries a "Rust port
(exploratory)" section from plan-104: the environment variable, what it changes,
how to revert, and that Windows stays Java-only. Keep it out of the README until
cutover — the README describes what users should use, and during the parallel
period that is still Java.

## Cutover

1. Flip the installer default from `java` to `rust`.
2. Rename the Rust assets to drop `-rs`; stop publishing the Java zips.
3. Keep `SHIPSMOOTH_ENGINE=java` working for a release or two as the escape hatch,
   then delete the branch, the Java CLI modules, `PackageRuntime`'s jlink path,
   and `ReleaseGuard`.

**Build-time Java is explicitly out of scope for removal.** Skill rendering
(`Target`, the jte templates), plugin packaging, and `PublishRelease` run on a
developer machine and in CI, where 450ms of startup is irrelevant. Hold that line,
or "eliminate Java" silently expands into rewriting the entire build and harness
layer — which is a much larger project than porting the CLI was, with none of the
user-visible benefit.

## Verification

- **Parity is the gate.** `exp/rust/parity/run.sh` must stay green before any
  release advertises the Rust engine. Note its `SS_JAVA` default is
  `~/.cache/shipsmooth/<ver>/bin/shipsmooth` — the exact path the symlink scheme
  repoints at Rust. **Pin `SS_JAVA` to the real Java path in the harness**, or it
  will silently diff Rust against itself and the parity signal becomes worthless
  precisely when it matters most.
- **Installer, offline, all branches.** `PosixBootstrapIntegrationTest` already
  stages a fake `XDG_CACHE_HOME` and overrides the download base via
  `SS_URL_BASE`. Add: rust engine, explicit java, invalid value (non-zero exit),
  and rust-then-java against one cache, asserting neither re-downloads the other.
- **Rendered output must not change.** `TargetIntegrationTest` asserts exact
  `cliBinPath` strings for prod, dev, and Windows. These must be **untouched** —
  that is the proof the plugin stayed engine-agnostic and the cutover really is
  one line.
- **Real round trip.** Cut a release against a scratch `SS_URL_BASE`; with a clean
  cache, run a session with the variable unset (expect Java) and with
  `SHIPSMOOTH_ENGINE=rust` (expect Rust). Confirm the symlink resolves and that
  `SKILL.md`'s `SS="…"` path invokes the selected engine.
- **Confirm by artifact, never by exit code.** Run `publishRelease` bare in the
  background — never piped through `tail`, whose status masks Gradle's — and
  verify with `gh release view` that all seven assets are present and the release
  is not a draft.

## Risks and gotchas

- **Windows is a different mechanism entirely.** `%LOCALAPPDATA%`, a generated
  `install-runtime.bat` that xcopies from the plugin cache with no download at
  all, a `shipsmooth.cmd` shim, and a force-pushed sibling repo. Ship Rust
  POSIX-only; leave `Os.Windows.cliBinPath` and the bat generation untouched.
  Windows Rust is its own plan, and needs `x86_64-pc-windows-msvc` in CI plus
  re-testing of the git-shelling paths (`Command` quoting differs from
  `ProcessBuilder`) — see 00-overview.md §Windows.
- **Cross-compilation is the real work.** `cargo-zigbuild` or a container per
  target is likely simpler than native runners; darwin-arm64 from Linux is the
  awkward one. Verify each binary actually runs on its target before the first
  release that advertises it.
- **Symlink portability.** Fine on macOS and Linux, the only Rust targets here.
  It does mean the link dangles if the Java runtime directory is absent, so the
  installer should create it only after the Rust extract succeeds, and remove it
  when switching back to `java`.
- **Engine ambiguity.** Two engines in one cache tree makes "which am I running"
  ambiguous from the filesystem alone. The installer must log the choice, and
  `--version` should identify the engine.
- **Help-text drift is now user-visible.** 00-overview.md notes clap's `--help`
  differs from picocli's, tolerable while Rust is an experiment. Once both ship
  under one version number, two users on the same release can see different help
  output. Machine contracts (JSON gate, exit codes) are parity-verified; check
  `SKILL.md` and harness prose for anything quoting human-readable help.
