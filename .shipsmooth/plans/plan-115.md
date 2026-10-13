# plan-115 — Ship the Rust CLI as `shipsmooth-beta`, a separate release line

**Backlog feature:** distribute the Rust CLI (`exp/rust/`) to real users (local
backlog; no external issue). It continues the rust-migration series: plan-102 found
the port feasible, and plans 104–110 closed parity. The design is in
[`docs/rust-migration/05-separate-beta-release.md`](../../docs/rust-migration/05-separate-beta-release.md)
(called **05** below). That doc replaces the parallel-period half of 04-shipping.md.

## Context

The Rust CLI is feature-complete and parity-verified: 60/60 scenarios are
byte-identical, `cargo test --workspace` gives 290/0, and clippy is clean (plan-110,
re-checked 2026-10-07). What remains is a distribution problem. The goal is a second
installable product, `shipsmooth-beta`. It is not a second engine behind a flag. It
has its own version series, plugin identity, cache tree, slash command and release
assets, so the maintainer can run it as a daily driver alongside `shipsmooth` and
promote it once it has proven itself.

Three properties come from 05 §Goal: (1) an independent version series, with each
release traceable to a `main` sha; (2) it visibly signals pre-release; (3) it coexists
with `shipsmooth` with no interference.

### What 05 already establishes (not re-litigated here)

- Name `shipsmooth-beta`; version strings `0.1.0-beta`, `0.1.0-beta.2`, … →
  tag `v0.1.0-beta` → asset `shipsmooth-beta-0.1.0-beta-<platform>.zip`. The tag can't
  collide with the 49 existing `v*` tags. (05 also claims `install-shipsmooth.sh` needs
  no change. That is wrong; see Finding 5.)
- `schema.location` uses an **unversioned** URL during the beta and goes back to
  version-pinned at promotion (05 §4, option 2).
- Use a separate packager that zips the single `bin/shipsmooth-beta` (0755), built with
  `release-small`. Do not extend `PackageRuntime`.
- The beta publish is gated behind a property (default off), skips `ReleaseGuard`, and
  replaces it with a `--version` check. Release notes use `Release v<ver> (main: <sha>)`.
- POSIX only (linux-x64, darwin-x64, darwin-arm64). Windows stays Java-only.
- Leave the README alone until promotion. Docs go in `EXPERIMENTAL.md`.

### Findings from checking 05 against the code (2026-10-07)

1. **`Os.launcherFileName()` cannot just "return `shipsmooth-beta`"** (05 §2). It is a
   no-argument constant on the shared `Os.Posix` record
   (`plugin-model/.../Os.java:34`), and both products render through it. Changing the
   literal would rename the Java product's launcher too and break every existing
   `shipsmooth` install. The launcher name has to be **derived from the plugin base
   name**, for example by passing `basePluginName` into `cliBinPath` (which already
   receives `pluginName`) and using it in place of the constant. The Windows branch
   stays hard-coded to `shipsmooth.cmd`, because Windows is out of scope.
2. **05 does not say where the beta's plugin payload is hosted.** The Java product's
   payload (rendered skills, hooks, `install-shipsmooth.sh`, manifests, schema) is
   committed to `dist/` on the `releases` branch by `syncDistAndPublish`, and users
   reach it through the separate `bitkentech/claude-plugins` marketplace repo. The beta
   needs its own payload location plus a marketplace entry. See Open question 1.
3. **The beta render needs the beta version as well as the beta name.**
   `plugin.version` (`harness/shared/build.gradle.kts:92`) is baked into the hook
   command, so the installer downloads `shipsmooth-beta-$VERSION`, and it also sets the
   cache path. A beta render therefore passes both
   `-Pplugin.base.name=shipsmooth-beta` and `-Pplugin.version=<cargo version>`. The
   source of truth for that version is the Cargo workspace version, not
   `gradle.properties`.
4. `harness/shared/build.gradle.kts:135` hard-codes `pluginBaseName = "shipsmooth"`, as
   05 §1 says. Leave `pluginRepoName` (`:141`) alone, because both products release
   from `bitkentech/shipsmooth`.

5. **(v2) `install-shipsmooth.sh` hard-codes the launcher name**, contrary to 05
   §"What the name already buys". `BIN="$RUNTIME_DIR/bin/shipsmooth"` (`:40`) and
   `EXTRACTED_BIN="$EXTRACT_DIR/bin/shipsmooth"` (`:65-66`) are literals, so a zip
   holding `bin/shipsmooth-beta` dies with "extracted archive is missing
   bin/shipsmooth". `bin/$NAME` is not a safe substitute, because the OpenCode dev
   render passes `NAME=shipsmooth-dev` with a Java `bin/shipsmooth`
   (`harness/shared/build.gradle.kts:256`). **Decision (2026-10-13): a separate
   `install-shipsmooth-beta.sh`**, which the beta's `hooks.json` invokes. The Java
   `install-shipsmooth.sh` is **not modified at all**, so no Java user runs new
   installer code. The beta script hard-codes `bin/shipsmooth-beta`, which makes the
   product explicit in its own hook command. It is near-duplicate shell (~70 lines);
   that drift risk is accepted because the beta line is temporary, and the shell tests
   run both scripts through the same install and idempotency cases. At promotion the
   beta script either becomes the main installer or is deleted. A considered
   alternative was an optional third `[launcher]` argument on the shared script; it was
   rejected because it edits the installer every `shipsmooth` user runs.
   `HookCommandRenderer` copies the installer only when the command contains
   `INSTALL_SCRIPT_NAME = "install-shipsmooth.sh"` (`:22`, `:43`), and
   `install-shipsmooth-beta.sh` does not contain that string, so the renderer must
   learn the second name.
6. **(v2) The hook commands hard-code the `shipsmooth` NAME argument**:
   `harness/shared/build.gradle.kts:213` (Claude, which also has the
   `plugins/cache/bitkentech/shipsmooth/$pluginVersion` fallback root), `:223`
   (Gemini), `:233` (Codex) and `:256` (OpenCode). The Claude beta render must pass
   `hooks/install-shipsmooth-beta.sh shipsmooth-beta <ver>` and use a `bitkentech/shipsmooth-beta`
   fallback root. Only `:213` is in scope (Claude only, Q2), but it must read the base
   name rather than gain a second literal.

### Design decisions

- **The beta release flow is a separate entry point**, for example a
  `PublishBetaRelease` class or `publishBetaRelease` task next to `PublishRelease`. It
  is not a branch inside `PublishRelease.run()`, so a failure in either flow cannot
  strand the other. It reuses `assertTagAbsent` and the provenance-note format.
- **The version comes from `exp/rust/Cargo.toml`** (`[workspace.package] version`),
  read once by the beta flow and passed to cargo, the packager, the plugin render and
  the tag. 05 §Risks calls the version string "load-bearing in three places", so one
  value feeds all of them.
- **`main.rs:226`** compares against the workspace version (via a build-time
  constant), not a literal.
- **Each task is checked by the artifact it produces**, not by exit code (05
  §Verification). Run publish tasks bare, not piped through `tail`.

### TDD invariant

Most tasks are build or distribution plumbing, but each has a testable seam: Rust unit
tests (`--version`, schema location), Java unit and integration tests (render, `Os`,
packager zip layout, gating, the version check), and the existing
`PosixBootstrapIntegrationTest` pattern for installing offline. The actual GitHub
publish and the push to the marketplace repo are outward-facing. They are **not**
automated by this plan's tests; they are listed as human-run steps under Verification.

**Preamble integration tests (written red before Task 1):**

- **IT-1, beta install end to end, offline.** Build the beta zip for the host
  platform, render the beta plugin, and run the rendered `install-shipsmooth-beta.sh` with
  `SS_URL_BASE` pointing at a local directory and a fake `XDG_CACHE_HOME`. Assert that
  `~/.cache/shipsmooth-beta/0.1.0-beta/bin/shipsmooth-beta --version` prints
  `0.1.0-beta`, and that the rendered skill's `SS=` path points at that exact file.
- **IT-2, coexistence.** In the same fake cache, also install the Java `shipsmooth`
  zip. Assert that both trees exist, that installing the second product does not cause
  a re-download of the first, and that each rendered `SS=` path resolves to its own
  product.

## Resolved at Phase 1 calibration (2026-10-13)

- Risk levels accepted as estimated (T1 High, T2 Medium, T3 High, T4 Low, T5 Low).
- Q1: orphan `releases-beta` branch, `dist/` payload, second marketplace entry.
- Q2: Claude only for 0.1.0-beta.
- Q3: root `.claude-plugin/plugin.json` stays `shipsmooth`-only.
- Q4: `cargo-zigbuild` first; linux-x64-only is the floor (minor deviation).
- Q5: coverage threshold **90%** for new Java/Kotlin code.
- **v2 (2026-10-13, major deviation before the preamble):** the installer hard-codes
  `bin/shipsmooth` (Finding 5). Decision: a separate `install-shipsmooth-beta.sh`
  invoked by the beta's `hooks.json`, leaving the Java installer untouched. Task 2
  stays **Medium** and now also covers the beta installer, renderer script detection,
  the lint task and the Claude hook command (Finding 6). Task 4 no longer treats the
  beta installer as a deviation.

## Open questions (as posed for Phase 1 calibration)

1. **Beta payload hosting and marketplace.** Recommendation: a **separate orphan
   branch `releases-beta`** in `bitkentech/shipsmooth` with the payload at `dist/`, and
   a second plugin entry (`shipsmooth-beta`) in the `bitkentech/claude-plugins`
   marketplace whose git source points at `ref=releases-beta, path=dist`. Pushing to
   the Java `releases` branch (as `dist-beta/`) would make the two flows contend for
   one branch, which breaks property 3. Editing the marketplace repo is outward-facing
   and stays a manual step.
2. **Hosts in scope.** Recommendation: **Claude only** for the first beta. Gemini,
   Codex and OpenCode read `plugin.base.name` the same way, so they can follow without
   design changes, but each adds a payload and a marketplace to verify.
3. **Root `.claude-plugin/plugin.json`** (05 §7). Recommendation: keep it
   `shipsmooth`-only. It is the repo's static manifest and is not part of either
   payload.
4. **Cross-compilation tooling.** Recommendation: try `cargo-zigbuild` for all three
   POSIX targets from Linux (Task 1 tests this approach). If darwin-arm64 fails, fall
   back to building darwin targets natively on a Mac host. That would be a minor
   deviation, with linux-x64-only as the floor for 0.1.0-beta.
5. **Test coverage threshold** for the new Java and Kotlin code (default 95%).

## Tasks

### Task 1: Beta binary — rename, version series, unversioned schema URL, cross-compile [High]

- `exp/rust/Cargo.toml` workspace version → `0.1.0-beta`; `ss-cli` `[[bin]] name =
  "shipsmooth-beta"`.
- `main.rs:226`: assert against the workspace version, not a literal. Check whether
  clap's `#[command(name)]` or usage text hard-codes `shipsmooth`, and decide whether
  help text says `shipsmooth-beta` (05 §Risks, help-text drift).
- `schema_config.rs:13`: emit the unversioned `releases`-branch URL; add a unit test
  that the emitted location contains no version.
- `exp/rust/build.gradle.kts`: a `cargoBuildBeta` task per POSIX target
  (`release-small`), following the `RustToolchain` + `onlyIf { found() }` skip
  convention; spike `cargo-zigbuild` for darwin-x64 and darwin-arm64.
- Parity: confirm the 60 scenarios still pass against a `-Pbuild.env=prod` Java build
  (the binary rename must not leak into machine output; check the schema-location
  normalisation by hand).
- **Risk:** high. Cross-compiling, especially darwin-arm64 from Linux, is the main
  unknown (05 §Risks), and the schema URL writes into shared user config.

### Task 2: Render the plugin as `shipsmooth-beta`, with its own installer [Medium]

- **Beta installer (Finding 5):** new `harness/shared/src/main/resources/install-shipsmooth-beta.sh`,
  derived from `install-shipsmooth.sh` with `bin/shipsmooth-beta` in place of
  `bin/shipsmooth` (the `:40` idempotency check, the `:65-66` extraction check and the
  `die` message). `install-shipsmooth.sh` stays **byte-identical**. Add the new script
  to the `lintInstallScript` task (`sh -n` + shellcheck). Shell-level tests run both
  scripts through the same install, exec-bit and idempotency cases.
- **Renderer:** `HookCommandRenderer` copies whichever known installer script the hook
  command names (today it only matches `install-shipsmooth.sh`, `:22`/`:43`). A
  `shipsmooth` render copies exactly the files it copies today.
- `harness/shared/build.gradle.kts:135` reads `plugin.base.name` (default
  `"shipsmooth"`); `pluginRepoName` is unchanged.
- **Claude hook command (Finding 6, `:213`):** the script name, NAME and fallback
  cache root come from the base name. The rendered `hooks.json` for `shipsmooth` stays
  byte-identical to today's. Gemini, Codex and OpenCode (`:223`, `:233`, `:256`) are
  untouched.
- `Os.Posix` derives the launcher name from the base plugin name (Finding 1); Windows
  is unchanged. Update `PluginModelTest` / `TargetIntegrationTest` and add beta cases
  (`.../shipsmooth-beta/0.1.0-beta/bin/shipsmooth-beta`) **while keeping** the existing
  `shipsmooth` assertions unchanged, which proves the Java product's render is
  untouched.
- A `renderBeta` entry point (Gradle task or property bundle) that passes both
  `plugin.base.name` and `plugin.version` (taken from Cargo), producing
  `build/…/shipsmooth-beta` with `plugin.json`/`marketplace.json` named
  `shipsmooth-beta` and the slash command `/shipsmooth-beta:start`. A dev render is
  `shipsmooth-beta-dev` (it composes with `Env.decorate`).
- **Risk:** medium. The Java installer is untouched, but the renderer's script
  detection, the `:213` hook command and `Os` sit on paths the `shipsmooth` render also
  goes through. Byte-identical checks on the `shipsmooth` output guard them. It stays
  ahead of the High-risk Task 3 because Task 3 publishes its output.

### Task 3: Beta packager + gated publish flow with `--version` guard [High]
*Depends-on: 1,2*

- A sibling packager (not `PackageRuntime`) that emits
  `shipsmooth-beta-<ver>-<platform>.zip` containing exactly `bin/shipsmooth-beta`
  (0755). Unit test the zip layout and file mode.
- `PublishBetaRelease`, gated by a property that defaults off (mirroring
  `PUBLISH_OPENCODE_NPM_DEFAULT`). It reads the Cargo version, runs `assertTagAbsent("v"
  + version)`, skips `ReleaseGuard`, runs the linux-x64 binary's `--version` and checks
  it equals the version, renders the beta plugin payload (from Task 2), pushes the
  payload to the `releases-beta` branch (Open question 1), tags, and runs
  `gh release create … --notes "Release v<ver> (main: <sha>)"` with the three zips.
  Unit tests cover the gate, the version-mismatch failure, tag collision, and the notes
  string; the git and `gh` calls go through the same `runCommand` seam that
  `PublishRelease` uses.
- **Risk:** high. This is the outward-facing release path and the first flow that
  touches a new branch and new asset names. A mistake here publishes a broken product
  under a permanent tag.

### Task 4: Installer coexistence (IT-2 hardening) [Low]
*Depends-on: 2,3*

- Extend `PosixBootstrapIntegrationTest` with the two-product case from IT-2, using the
  real beta zip from Task 3 and the rendered beta hook from Task 2 (which invokes
  `install-shipsmooth-beta.sh`).
- **Risk:** low. Task 2 has already added the beta installer, so this task only adds
  the two-product case to an existing test pattern.

### Task 5: Docs — `EXPERIMENTAL.md` beta section + release runbook [Low]

- `EXPERIMENTAL.md` "Rust port": install path (`/plugin install
  shipsmooth-beta@bitkentech`), the version scheme, the fact that it is a separate
  product (with its own cache tree and slash command, sharing
  `~/.config/shipsmooth/shipsmooth.toml`), POSIX-only, and how to uninstall.
- A maintainer runbook (in `DEVELOPMENT.md` or `docs/rust-migration/`) for cutting a
  beta: bump the Cargo version, run the publish task bare, check with `gh release
  view`, pin-the-pair parity record, check `schema.location` by hand, and edit the
  marketplace repo.
- `README.md` is untouched.
- **Risk:** low. Prose only.

## Verification (beyond task tests)

- `./gradlew build` at the root stays green on a machine **without** a Rust toolchain
  (the beta tasks skip rather than fail).
- Java product render: the existing `shipsmooth` cliBinPath assertions pass
  unmodified.
- Parity: 60/60 against a `-Pbuild.env=prod` Java build at the same sha.
- **Human-run, outward-facing (not automated, confirm before each step):** cut
  `v0.1.0-beta` with the publish task run bare; `gh release view v0.1.0-beta` shows 3
  assets and is not a draft; add the marketplace entry in `bitkentech/claude-plugins`;
  run `/plugin install shipsmooth-beta@bitkentech` on a real machine;
  `/shipsmooth-beta:start` runs `~/.cache/shipsmooth-beta/0.1.0-beta/bin/shipsmooth-beta`;
  a config the beta writes has the unversioned `schema.location`, and that URL returns
  200.

## Out of scope

Windows Rust; the non-Claude hosts (unless Open question 2 decides otherwise);
promotion (05 §Promotion); deleting the Java CLI; the 04-shipping `SHIPSMOOTH_ENGINE`
switch.

## Backlog Issue

Local backlog: "Distribute the Rust CLI to real users" (no external issue). Continues
the rust-migration series (plans 102–110).
