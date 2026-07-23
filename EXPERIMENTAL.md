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

The port has no shipping path yet and is **not part of the main Gradle build** — the
root `settings.gradle.kts` does not include it, so `./gradlew build` at the repo root
cannot see this project or invoke cargo.

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