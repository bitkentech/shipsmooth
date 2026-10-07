# `shipsmooth-beta`: the Rust CLI as a separate release line

Scope: shipping the Rust CLI as its own user-visible product with its own version
series, verified by the maintainer over a parallel period, and promoted to the
default only once proven. Companion files: [00-overview.md](00-overview.md) §"Risks
and gotchas" flags release work as the deferred follow-up; [04-shipping.md](04-shipping.md)
is the *earlier* answer to the same question and is **superseded in its parallel-period
half** (see §"Why this replaces 04-shipping's parallel period") while its packaging
and `PublishRelease` analysis stays valid; [02-cli.md](02-cli.md) records the CLI as
feature-complete and parity-verified.

Prerequisite state (plan-110, 2026-08-28; re-verified 2026-10-07): 60 parity scenarios
byte-identical, `cargo test --workspace` 290 passed / 0 failed, clippy clean. Nothing
remains to port. What follows is entirely a distribution problem.

## Goal

**Publish the Rust CLI as `shipsmooth-beta` — a second installable product, not a
second engine behind a flag — so the maintainer can install and verify it repeatedly
against real use, then promote it to the default once confident.**

Three properties drive the decisions below.

### 1. Independent version series, traceable to a commit

`shipsmooth-beta` numbers itself. It does **not** track `plugin.version`. Each beta
release records the `main` commit it was built from, so any parity claim is
reproducible months later: given `(main: abc1234)`, check out `abc1234`, build the
Java CLI, and run the harness against that exact pair.

This is the deliberate inversion of 04-shipping.md §4, which treated the
Cargo-vs-`gradle.properties` drift as "the single largest consistency defect" because
it wanted one version line covering both engines. Dropping that requirement dissolves
the defect rather than fixing it — the drift becomes the design.

### 2. Visibly pre-release

The name is exposed to users through the marketplace, the plugin list, and the slash
command. It must say "bleeding edge" on sight, regardless of what the parity numbers
say. A user who installs `shipsmooth-beta` should expect to be an early adopter.

Note this is a *framing* choice, not a claim about verification: as of plan-110 the
Rust CLI is arguably the better-tested of the two. The warning is about operational
maturity — it has never been installed by anyone, on any machine, as a daily driver.

### 3. Both products coexist with no interference

Separate cache trees, separate plugin identities, separate slash commands, separate
release assets. Installing one must not touch, upgrade, or shadow the other, and it
must always be unambiguous which one just ran.

## Naming: settled as `shipsmooth-beta`

| Candidate | Rejected because |
|---|---|
| `shipsmooth-rs`, `shipsmooth-rust` | Names the *implementation*. "rs" is Rust-ecosystem jargon and meaningless to a user reading a marketplace listing; the port's defining property is that it behaves identically, so the language is the least useful thing to put in the name |
| `shipsmooth-edge`, `shipsmooth-canary` | Both name a **permanent** channel that runs ahead of stable forever (Docker Edge, Chrome Canary). This line is temporary by construction — it graduates and is deleted — so the name would set up an expectation that has to be explained away at promotion |
| `shipsmooth-alpha` | Oversells the risk. 60/60 parity, nothing missing or feature-gated |
| `shipsmooth-nightly` | Mis-signals cadence. Verification here is manual and episodic, not an automated nightly cut |
| `shipsmooth-preview` | Viable, same graduation semantics as `-beta`; rejected only as the softer of the two, and the point is to warn people off |
| `shipsmooth-dev` | **Unavailable.** `Env.decorate` returns `base + "-dev"` (`Env.java:15`), so `~/.cache/shipsmooth-dev/` is already the dev-build tree. Picking it collides with the inner loop |

`-beta` wins on one property the alternatives lack: **it graduates.** "The beta became
the real thing, the old one was retired" is a story users already understand from every
other product, and it requires no announcement to make sense. `-edge` and `-canary`
would each need an explanation for where the channel went.

The suffix also composes with the existing dev dimension without special-casing: a dev
build of the beta is `shipsmooth-beta-dev`, which is unambiguous.

## What the name already buys, for free

The single most important finding for costing this work: **`install-shipsmooth.sh`
needs no changes at all.** It is already fully parameterised by its first argument.

| Line | Expression | With `NAME=shipsmooth-beta` |
|---|---|---|
| `:16` | `NAME="${1:?usage: …}"` | passed in by the rendered hook command |
| `:19` | `CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/$NAME"` | `~/.cache/shipsmooth-beta/` |
| `:56` | `URL="$URL_BASE/$NAME-$VERSION-$PLATFORM.zip"` | `shipsmooth-beta-<ver>-<platform>.zip` |
| `:22` | `log() { printf '%s: %s\n' "$NAME" …; }` | every log line self-identifies |
| `:51` | `TMP=$(mktemp -d "…/$NAME-XXXXXX")` | no temp-dir collision between products |

This is the decisive cost difference against 04-shipping.md, which required a new
engine-resolution block in the installer **plus** a symlink at the Java cache path so
`Os.cliBinPath` could stay byte-identical. A separate product needs neither: the cache
path is *supposed* to differ, so the symlink's whole reason to exist disappears.

The plugin identity is likewise already a build input, not a constant: all four host
harnesses read `plugin.base.name` as a Gradle property with a `"shipsmooth"` default
(`harness/claude/build.gradle.kts:13`, `gemini:24`, `codex:17`, `opencode:22`), it
reaches `Target` via `System.getProperty("plugin.base.name")` (`Target.java:51`), and
the Claude manifest template interpolates it as `${plugin.name}`
(`harness/claude/src/main/resources/claude-plugin/plugin.json`).

So most of the rename is `-Pplugin.base.name=shipsmooth-beta`.

## Changes

### 1. Close the one hardcoded `pluginBaseName`

`harness/shared/build.gradle.kts:135` sets `pluginBaseName = "shipsmooth"` as a literal
inside `baseSpec(env)`, while every host harness reads the property. Make it read
`plugin.base.name` with the same `?: "shipsmooth"` default, so one property governs all
five and a beta render cannot silently half-apply.

**Do not change `pluginRepoName` (`:141`).** That names the GitHub repo, which is
unchanged — both products ship from `bitkentech/shipsmooth`. Conflating the two would
break the release URLs.

### 2. Binary name

Set `[[bin]] name = "shipsmooth-beta"` (`exp/rust/crates/ss-cli/Cargo.toml:10`) and have
`Os.launcherFileName()` return it (`Os.java:34` POSIX, `:60` Windows).

Strictly this is optional — the installer never puts the CLI on `PATH`, and skills
invoke it by absolute path through `SS="${model.cliBin()}"`
(`skills/shared/workflow/task-tracking-mode.jte.md:11`), so two identically-named
binaries in different cache trees would not actually collide. Rename anyway, for
`ps`/`htop` legibility and so a hand-run command is self-identifying.

Expect `TargetIntegrationTest` and `PluginModelTest` to fail on their exact-string
`cliBinPath` assertions. **Update them.** This is the deliberate opposite of
04-shipping.md §Verification, which required those assertions stay untouched as proof
the plugin had stayed engine-agnostic; here the rendered path is *meant* to change.

### 3. Version series and the tag namespace

The beta series must not reuse an existing tag. **49 `v*` tags already exist, spanning
`v0.1.0` → `v0.3.36`**, so a beta line naively started at `0.1.0` would collide on its
first release. `PublishRelease.run()` calls `assertTagAbsent("v" + version)` (`:95`), so
this fails loudly rather than corrupting anything — but it has to be designed around,
not discovered.

`URL_BASE` hardcodes `releases/download/v$VERSION` (`install-shipsmooth.sh:20`), so the
tag is always `v` + the version string verbatim. The cheapest collision-free scheme is
therefore to put the distinction **in the version string**:

```
0.1.0-beta   →  tag v0.1.0-beta  →  shipsmooth-beta-0.1.0-beta-linux-x64.zip
```

Valid semver prerelease, accepted by Cargo, unique against all 49 existing tags, and
needs no installer change. Use `0.1.0-beta.2`-style increments if several betas target
one milestone.

The doubled `beta` in the asset name is ugly. If that grates, the alternative is a bare
`0.1.0` series plus a `beta-v$VERSION` tag prefix — but that *does* require touching
`URL_BASE`, so it trades the cosmetic problem for a real one. Recommendation: accept the
ugly asset name.

Also update `crates/ss-cli/src/main.rs:226`, which asserts
`env!("CARGO_PKG_VERSION") == "0.3.34"` against a hardcoded literal. Compare against the
workspace version instead, per 04-shipping.md §4's second point — that fix is still
wanted, just no longer as a *synchronisation* mechanism.

### 4. `schema.location` — decide before the first release

**This is the one item that writes into shared user state, and the only one that is
painful to walk back.**

`ss-cli/src/ds/schema_config.rs:13` bakes the emitted `[toml-schema] location` from the
Cargo version:

```
https://raw.githubusercontent.com/bitkentech/shipsmooth/v{CARGO_PKG_VERSION}/dist/schemas/shipsmooth.tosd
```

Java does the same from `plugin.version` via `BuildEnv.prodSchemaUrl` (`BuildEnv.kt:59`).
Today Cargo reads `0.3.34`, which happens to be a real Java release tag — so the URL
resolves **by coincidence**. Verified 2026-10-07: both `v0.3.36` and `v0.3.34` return 200.

Under an independent series that coincidence is gone. `v0.1.0-beta` will 404 unless the
beta release also publishes `dist/schemas/shipsmooth.tosd`, and every `shipsmooth.toml`
the beta writes will carry a dead URL. Worse, both products write this key into the
*same* config file, so they overwrite each other's value.

Two options:

1. **Publish the schema per beta tag**, the same way `syncDistAndPublish` already stages
   it for a Java release. Keeps the version-pinned guarantee that a config points at the
   schema of the build that wrote it. Costs a `dist/schemas/` stage in the beta flow.
2. **Point the Rust build at an unversioned URL** (e.g. the `releases` branch tip).
   Simpler, and makes the two products agree on the key so they stop fighting — at the
   cost of losing the pin.

Recommendation: **(2) for the beta period, (1) at promotion.** During the parallel
period, agreement between the two products on a shared config key matters more than the
pin, and it removes a whole class of confusing diffs. Either way, note that the parity
harness currently normalises this token to `v<VERSION>` (plan-106) — so the harness will
*not* catch a regression here. Check it by hand.

### 5. Packaging: a separate packager, as 04-shipping already concluded

04-shipping.md §2 still applies verbatim and needs no revision: **do not extend
`PackageRuntime`.** It emits an SCC-warming shell launcher
(`-Xshareclasses:name=shipsmooth_v<ver>`) wrapping a `runtime/` jlink tree, all
meaningless for a single static binary. Add a sibling packager that zips one file,
`bin/shipsmooth-beta`, mode 0755, built with the `release-small` profile already defined
in `exp/rust/Cargo.toml` (~2.3 MB).

Wire the cross-compile into `exp/rust/build.gradle.kts`, reusing its `RustToolchain`
resolution and its `onlyIf { toolchain.found() }` skip-don't-fail convention.

POSIX only. Windows stays Java-only — see §Risks.

### 6. `PublishRelease`: gate it, and reuse the provenance convention

Provenance is **already implemented** and needs no new mechanism.
`PublishRelease.java:98` captures `git rev-parse --short HEAD` after the version bump and
threads it into the release notes at `:342` as `Release v<ver> (main: <sha>)` — which is
exactly what v0.3.36 carries today (`Release v0.3.36 (main: 5b65fd6)`). Emit the same
line for the beta, and the §1 traceability requirement is met.

Keep 04-shipping.md §3's two safety rules, which are independent of the naming scheme:

- **Gate behind a property, default off**, mirroring `PUBLISH_OPENCODE_NPM_DEFAULT`.
  Plan-89 fixed exactly this failure class: npm auth failing mid-flow stranded the GitHub
  and Windows releases half-done.
- **Skip `ReleaseGuard`** — it disassembles baked `Build.class` constants out of jlink
  images with `jimage`/`javap` and hard-fails the release if it cannot run, so it must be
  skipped rather than fed Rust artifacts. Replace it for the beta with a minimal
  equivalent: exec the linux-x64 binary's `--version` and assert it matches the release
  version.

A fully separate pipeline gets the "a Rust failure can never strand a Java release"
property almost for free, but the gate is still worth having — it keeps a missing Rust
toolchain from turning a routine Java release into a debugging session.

### 7. Docs

`EXPERIMENTAL.md` already carries a "Rust port (exploratory)" section from plan-104;
extend it with the beta's install path, its version scheme, and the fact that it is a
separate product rather than a mode of the main one. Keep it out of `README.md` until
promotion — the README describes what users *should* use, and during the parallel period
that is still `shipsmooth`.

Check the static root `.claude-plugin/plugin.json` separately. It is **not** the
templated manifest (that lives under `harness/claude/src/main/resources/claude-plugin/`
and is token-filtered), so `plugin.base.name` does not reach it. Decide whether the beta
needs its own, or whether that file stays `shipsmooth`-only.

## Why this replaces 04-shipping's parallel period

| | 04-shipping.md | this |
|---|---|---|
| Selection | `SHIPSMOOTH_ENGINE` env var | install a different product |
| Version | one line for both engines | independent series |
| Prerequisite | §4 version reconciliation **must** land first | none |
| Installer | new engine block + symlink | **no change** |
| `cliBinPath` | must stay byte-identical | changes, deliberately |
| Cutover | invisible default flip | a migration users participate in |
| Can start | after the prerequisite | now |

The honest trade: 04-shipping.md's stated goal was *"cutover is a default flip, not an
event users participate in"* — same asset name, same cache path, so a user who sets
nothing simply gets a faster binary one day. This plan gives that up. Promotion here
means people who installed `shipsmooth-beta` have to move again.

That is accepted because the blocking prerequisite is gone and the work can start
immediately, and because 04-shipping.md's symlink scheme is not *discarded* — it stays
available at promotion, when it is one script instead of a precondition.

## Verification

- **Parity stays the gate**, and it now compares binaries from two different trees. Two
  traps, both already hit in practice:
  - `parity/run.sh:21` auto-prefers `cli/build/install/cli/bin/cli` with **no check on
    which build variant it is**. `BuildEnv.from(null)` defaults to `DEV`
    (`BuildEnv.kt:63`), and a DEV build bakes a `file://` schema location — so a plain
    `./gradlew :cli:installDist` produces 7 red store scenarios that look like real
    divergences. Always build the Java side with `-Pbuild.env=prod`, or teach the harness
    to assert the discovered launcher's baked location before trusting it.
  - `SS_JAVA` defaults to `~/.cache/shipsmooth/<ver>/bin/shipsmooth` (`:25`). That stays
    correct under this plan (the beta lives in its own tree), unlike under
    04-shipping.md's symlink scheme — but pin it explicitly when checking a specific
    released pair.
- **Pin the pair.** A beta release's `(main: <sha>)` line is the input to its own parity
  record: build Java at that sha with `-Pbuild.env=prod`, run all 60 scenarios, record the
  result against the beta version.
- **Installer, offline, both products.** `PosixBootstrapIntegrationTest` already stages a
  fake `XDG_CACHE_HOME` and overrides the download base via `SS_URL_BASE`. Add a case
  installing both products into one cache tree and asserting neither re-downloads or
  shadows the other.
- **`schema.location` by hand**, every release. The harness normalises it away.
- **Confirm by artifact, never by exit code.** Run the publish task bare — never piped
  through `tail`, whose status masks Gradle's — and verify with `gh release view` that the
  expected assets are present and the release is not a draft.

## Promotion

1. Build the Rust binary under the `shipsmooth` name and version line; publish it as the
   standard assets.
2. Switch `schema.location` back to a version-pinned URL (§4 option 1).
3. Stop publishing the Java zips. Keep one or two releases of overlap.
4. Deprecate `shipsmooth-beta` in the marketplace, pointing at `shipsmooth`.
5. Delete the Java CLI modules, `PackageRuntime`'s jlink path, and `ReleaseGuard`.

At step 1, 04-shipping.md's symlink idea becomes useful again as a courtesy to anyone
still on `shipsmooth-beta`: a link from the beta cache path to the new one lets their
existing rendered skill keep working through one more release.

**Build-time Java stays.** Skill rendering (`Target`, the jte templates), plugin
packaging, and `PublishRelease` run on developer machines and in CI, where 450 ms of
startup is irrelevant. Hold that line, or "eliminate Java" silently expands into
rewriting the entire build and harness layer — a far larger project than porting the CLI
was, with none of the user-visible benefit.

## Risks and gotchas

- **The version string is load-bearing in three places** — the git tag, the asset
  filename, and the baked `schema.location`. They derive from one value, so a malformed
  version breaks all three at once. `assertTagAbsent` catches the collision case early;
  nothing catches a typo that happens to be tag-free.
- **Two products, one config file.** Both write `~/.config/shipsmooth/shipsmooth.toml`
  and both read each other's writes. State compatibility is parity-verified in both
  directions (02-cli.md §definition of done), but `schema.location` is the one key where
  they legitimately disagree — which is why §4 is a release blocker and not a cleanup.
- **Windows is a different mechanism entirely**: `%LOCALAPPDATA%`, a generated
  `install-runtime.bat` that xcopies from the plugin cache with no download, a `.cmd`
  shim, and a force-pushed sibling repo. Ship the beta POSIX-only and leave
  `Os.Windows.cliBinPath` and the bat generation untouched. Windows Rust is its own plan
  and needs `x86_64-pc-windows-msvc` in CI plus re-testing of the git-shelling paths
  (`Command` quoting differs from `ProcessBuilder`) — see 00-overview.md §Windows.
- **Cross-compilation is the real work.** `cargo-zigbuild` or a container per target is
  likely simpler than native runners; darwin-arm64 from Linux is the awkward one. Verify
  each binary actually runs on its target before the release that advertises it.
- **Help-text drift becomes user-visible.** 00-overview.md notes clap's `--help` differs
  from picocli's, tolerable while Rust is an experiment. Two users on different products
  will now see different help output. Machine contracts (JSON gate, exit codes) are
  parity-verified; check `SKILL.md` and harness prose for anything quoting human-readable
  help.
- **A beta nobody installs proves nothing.** The entire value of this plan over leaving
  `exp/rust` alone is real installs on real machines doing real work. If the beta ships
  and is not actually used as a daily driver, it has bought a release pipeline and no
  confidence.
