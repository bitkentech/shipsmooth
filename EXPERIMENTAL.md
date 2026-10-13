## Experimental

These features are in active development and may change.

### Java runtime for `shipsmooth`

Task tracking commands (`update-status`, `add-comment`, `set-commit`, etc.) are now backed by a jlink-packaged Java runtime rather than Node.js scripts. The runtime bundles a minimal JRE and is installed to `~/.cache/shipsmooth/{version}/` by the session-start hook. It starts in ~150 ms via OpenJ9's shared class cache and requires no separate JDK installation.

The CLI entry point is `shipsmooth` and exposes subcommands for every task-tracking operation. The module is at `plugin-tasks-java/`.

> **Removed (plan-82):** the experimental ledger-backed execution trace and the
> parallel coding-subagent subsystem (worker/integrate/worktree commands and the
> content-addressed object store) have been removed to reduce surface area. They
> may be re-introduced in a different form later; the formal model lives at
> `exp/model/`.

### Rust port (exploratory)

An exploratory Rust port of the core plan/task logic lives at `exp/rust/` (a Cargo
workspace: `ss-core` library + `ss-cli` binary, with a golden fixture corpus generated
from the Java CLI). The plan-102 spike returned a GO verdict on feasibility and
footprint: ~2 MB binary, ~3.8 MB RSS, <10 ms startup, versus ~103 MB runtime, ~69 MB
RSS, ~450 ms for the jlink-packaged Java CLI. See `docs/rust-migration/` for the
migration notes.

The port ships as a separate, opt-in plugin, `shipsmooth-beta` (below). It is still
**not part of the main Gradle build**: the root `settings.gradle.kts` does not include it,
so `./gradlew build` at the repo root cannot see this project or invoke cargo.

#### shipsmooth-beta: the Rust CLI as a separate plugin

`shipsmooth-beta` is the Rust CLI packaged as **its own Claude Code plugin**. It is not a
mode or a flag of `shipsmooth`. It is bleeding edge: it passes the same 60 parity
scenarios as the Java CLI, but it has had far less real-world use. Install it if you
want to try it as a daily driver and report problems.

```text
/plugin marketplace add bitkentech/claude-plugins
/plugin install shipsmooth-beta@bitkentech
```

Then start work with `/shipsmooth-beta:start`. What to expect:

- **It is separate from `shipsmooth`.** It has its own slash command, its own runtime
  under `~/.cache/shipsmooth-beta/<version>/bin/shipsmooth-beta` (a single ~2 MB static
  binary, no JRE), and its own installer. Both plugins can be installed at once without
  touching each other.
- **Its versions are its own series:** `0.1.0-beta`, `0.1.0-beta.2`, …. They are not tied
  to `shipsmooth`'s version numbers. Each GitHub pre-release names the `main` commit it was
  built from.
- **It sees your existing projects.** It reads `~/.config/shipsmooth/shipsmooth.toml`,
  where `shipsmooth` records each project's plan location, so repos you already use work
  as-is.
- **It never writes `shipsmooth.toml`.** Its first config write copies that file to
  `shipsmooth-beta.toml` next to it, and all of its writes go there from then on. It
  reads `shipsmooth-beta.toml` first, then falls back to `shipsmooth.toml` for any
  project the copy lacks, so projects you set up later with `shipsmooth` are still found.
  The reverse does not hold for `separate-dir` projects: one set up through the beta is
  recorded only in `shipsmooth-beta.toml`, so `shipsmooth` will ask the first-run question
  for it again. `same-repo` projects are unaffected, because both products find them from
  the repo's `.shipsmooth/` folder.
- **POSIX only** (linux-x64, darwin-x64, darwin-arm64). On Windows, keep using `shipsmooth`.

To remove it: `/plugin uninstall shipsmooth-beta`, then delete `~/.cache/shipsmooth-beta/`
and, if present, `~/.config/shipsmooth/shipsmooth-beta.toml`. Your `shipsmooth.toml` and
your plans are untouched.

Maintainers cut beta releases with `publishBetaRelease`. See
[`packaging/README.md`](packaging/README.md#shipsmooth-beta-release-rust-cli).

`exp/rust/` is instead a self-contained Gradle build with its own settings file and
wrapper, driven from that directory:

```bash
cd exp/rust
./gradlew cargoBuild    # or cargoTest / cargoClean
```

The tasks locate cargo via `-Pcargo.home=<dir>`, `$CARGO_HOME`, or `PATH` (plus
`-Prustup.home`/`$RUSTUP_HOME` for rustup-managed toolchains) and skip with a message
when no Rust toolchain is installed. Plain `cargo build` / `cargo test` from
`exp/rust/` works equally well. The wrapper here is a copy of the root one — bump both
together on a Gradle upgrade.

Note that `cargoBuild` runs a plain `cargo build`, which is a **debug** build. For an
optimized binary, invoke cargo directly as below.

#### Building and running the Rust CLI

All commands run from `exp/rust/`. If cargo is not on your `PATH`, export the toolchain
location first (this repo keeps it under `/opt`):

```bash
export CARGO_HOME=/opt/cargo RUSTUP_HOME=/opt/installers/rustup
export PATH="$CARGO_HOME/bin:$PATH"
```

Build a release binary:

```bash
cd exp/rust
cargo build --release
```

The binary is written to `target/release/shipsmooth-beta`. It is named after the
`shipsmooth-beta` product, not `ss-cli`, even though the crate is `ss-cli`. Its usage and
error text still say `shipsmooth`, so its output matches the Java CLI byte for byte.
Launch it directly:

```bash
./target/release/shipsmooth-beta --help
./target/release/shipsmooth-beta --version   # -> 0.1.0-beta (bare, like the Java CLI)
./target/release/shipsmooth-beta plan --help
```

The port currently exposes three command groups — `store`, `plan`, and `task` —
mirroring their Java counterparts. Run it from within a project directory, exactly as
you would the Java CLI. It resolves state through the same `store` config, reading
`shipsmooth.toml` but writing only `shipsmooth-beta.toml` (see above).

To build and launch in one step (useful while iterating), `cargo run` forwards
everything after `--` to the binary:

```bash
cargo run --release --quiet -- plan --help
```

Drop `--release` for a faster-compiling debug build at `target/debug/shipsmooth-beta`.

There is also a size-optimized `release-small` profile (`opt-level = "z"`, LTO, symbols
stripped, `panic = "abort"`) — this is the profile behind the ~2 MB footprint figure
quoted above, and it is what to use when measuring binary size:

```bash
cargo build --profile release-small   # -> target/release-small/shipsmooth-beta
```

For reference, a local build produces ~4.6 MB for `release` versus ~2.3 MB for
`release-small`. Because `panic = "abort"` drops unwinding, prefer plain `--release`
for day-to-day work and reserve `release-small` for footprint measurements.

The release binaries are cross-compiled for every POSIX platform from one Linux host
with `./gradlew betaBinaries verifyBetaBinaries` (needs `cargo-zigbuild` and zig). See
[`packaging/README.md`](packaging/README.md#shipsmooth-beta-release-rust-cli).
