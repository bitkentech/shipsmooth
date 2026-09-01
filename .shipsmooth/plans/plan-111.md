# Plan 111 — `manifest.toml`: a shipsmooth-owned-folder marker

## Context

**Backlog feature:** PB-360 — "Implement a shipsmooth-owned folder manifest
marker". The CLI currently *infers* whether a `.shipsmooth/` directory is
genuine shipsmooth state (`Files.isDirectory(<data-root>/plans)`) or a
coincidentally-named folder. Write a small marker file at creation time so
"is this folder shipsmooth's?" becomes a recorded fact, and teach the
resolver to consult it.

**Design, settled with the requester (scoped deliberately small):**

- File: `manifest.toml` — TOML, matching the existing `shipsmooth.toml`
  config machinery. Lives at the **data root**:
  - in-repo mode: `<repo>/.shipsmooth/manifest.toml`
  - separate-dir mode: `<state-root>/manifest.toml`
- Contents — just enough to say "shipsmooth made this", plus provenance:
  ```toml
  [shipsmooth]
  kind = "state-store"
  cli-version = "0.3.36"      # the CLI build that created the folder

  [manifest-schema]
  version = "1"
  ```
- **Explicitly out of scope** (the requester called these "fragile, rare"):
  repo-identity / origin-URL matching, a per-store UUID, detecting a copied
  or moved store, schema-version migration, a `store repair` command.

**Backward-compatibility constraint (the main risk).** This repo already has
90+ `.shipsmooth/plans/*.xml` with no manifest, as does every existing user.
The resolver must **not** start treating a populated `.shipsmooth/plans/` as
unsettled just because the marker is absent. The marker is **purely additive**:
present + valid → a strong, authoritative "this is in-repo shipsmooth state"
signal; absent → the resolver behaves exactly as it does today. `resolve()` is
detection-only and must stay that way — no self-healing writes on the read path
(the stricter "reject unmarked folders" behaviour PB-360 sketches needs a
migration/heal story that is out of scope here; noted for a follow-up).

**Two implementations.** The shipped Java tree AND the `exp/rust/` twin, kept in
lockstep so the plan-110 parity harness stays green. The manifest's
`cli-version` field will read `0.3.36` (Java) vs `0.3.34` (Rust) — the same
deliberate version split plan-106/110 already handle — so the parity harness
must normalise that one field.

## Key code (verified 2026-08-28, at `main` `f3c7cc0`)

| Concern | Java | Rust twin |
|---|---|---|
| path registry / data root | `core/.../conf/ShipsmoothDataLocator.java` (`dataRoot()` private, `plansDir()` etc.) | `exp/rust/crates/ss-core/src/conf/locator.rs` (`data_root()` fn, `plans_dir()`) |
| user config value type | `cli/.../conf/ds/StandaloneConfig.java` (+ `TomlSchemaRef`) | `exp/rust/crates/ss-cli/src/ds/config.rs` (serde, `deny_unknown_fields`) |
| atomic TOML writer | `cli/.../conf/ds/ConfigWriter.java` (temp-file → `ATOMIC_MOVE`; `ArrayOfTablesTomlEmitter`) | `exp/rust/crates/ss-cli/src/ds/config_writer.rs` (`toml_edit`) |
| resolver | `cli/.../conf/ds/ProjectDataStoreResolver.java` (`fromFilesystem`, `fromInRepoEntry` — both key off `Files.isDirectory(DATA_DIR/PLANS_SUBDIR)`) | `exp/rust/crates/ss-cli/src/ds/resolver.rs` |
| `store init` action | `cli/.../store/Init.java` `act()` (EXTERNAL / RECREATE_MISSING_DIR / IN_REPO) | `exp/rust/crates/ss-cli/src/store/init.rs` `act()` |
| build CLI version | `io.bitken.ss.Build.VERSION` (`core/.../java-templates/.../Build.java`, `${project.version}`) | `env!("CARGO_PKG_VERSION")` |
| parity harness | — | `exp/rust/parity/run.sh` (60 scenarios; `capture_scenario` already normalises the toml schema-version) |

Coverage threshold: **95%** for net-new code, both languages (Rust: `cargo
llvm-cov`; Java: the repo's existing coverage gate). clippy-clean for Rust.

---

### Task 1: `manifestFile()` path accessor + shared filename constant [Low]

Add the manifest path to the single source of truth for path construction, so
nothing else hardcodes the filename.

- **Java** `ShipsmoothDataLocator`: `public static final String MANIFEST_FILE =
  "manifest.toml";` and `public Path manifestFile() { return
  dataRoot().resolve(MANIFEST_FILE); }`.
- **Rust** `locator.rs`: `pub const MANIFEST_FILE: &str = "manifest.toml";` and
  `pub fn manifest_file(&self) -> PathBuf { self.data_root().join(MANIFEST_FILE) }`.
- Tests: both modes (in-repo → `<repo>/.shipsmooth/manifest.toml`; separate-dir
  → `<state-root>/manifest.toml`), mirroring the existing
  `plans_dir_is_dotfolder_in_repo_but_bare_in_standalone` test.

Low risk: a pure path derivation with an established pattern, no behaviour
change. It is a hard dependency for Tasks 3 and 4, so it goes first.

### Task 2: `Manifest` value type + atomic reader/writer [Medium]

*Depends-on: 1*

<!-- execution order (risk-sorted, with the Task 1 dependency exception):
     1 (Low, hard dep) -> 2 (Med, dep) -> 3 (High) -> 4 (Med) -> 5 (Med, dep on 4) -->


A small TOML value type mirroring `StandaloneConfig`'s style, plus load/store.

- **Java** — `cli/.../conf/ds/Manifest.java`: a value type
  (`ShipsmoothSection{kind, cliVersion}`, `ManifestSchemaRef{version}`) with
  Jackson `@JsonProperty`. `Manifest.current()` builds one stamped with
  `Build.VERSION` + `kind="state-store"` + schema `version="1"`. A writer that
  serialises and does the same temp-file-then-`ATOMIC_MOVE` dance as
  `ConfigWriter.writeAtomically` (extract/share that helper if clean). A lenient
  reader: `Optional<Manifest> read(Path)` — absent / unreadable / unparseable →
  `Optional.empty()` (same spirit as `parseConfig`).
- **Rust** — `exp/rust/crates/ss-cli/src/ds/manifest.rs`: a serde struct
  (`deny_unknown_fields`), `Manifest::current()` stamped with
  `env!("CARGO_PKG_VERSION")`, an atomic writer matching `config_writer.rs`, and
  `Manifest::read(&Path) -> Option<Manifest>`.
- The emitted bytes must be identical between the two **except** `cli-version`.
  Pin the exact serialisation (key order, quoting, trailing newline) with a
  golden-string test on each side.
- Tests: round-trip; `current()` carries the build version; atomic write leaves
  no temp litter on a simulated serialize failure (mirror `ConfigWriterTest` /
  the Rust `config_writer` test); unparseable file → `empty`/`None`.

Medium: new file format + a file-write primitive, but a very close analogue of
`ConfigWriter` exists on both sides to copy.

### Task 3: resolver consults the manifest as an authoritative in-repo signal [High]

*Depends-on: 1,2*

Teach `ProjectDataStoreResolver` / `resolver.rs` to read the marker — additive
only:

- In the **filesystem-fallback** path (no matching config entry) and the
  **`fromInRepoEntry`** path (config says same-repo): if
  `manifestFile()` **exists and parses** as a `kind = "state-store"` manifest →
  `Settled(InRepo)` — authoritative, even if `plans/` is somehow absent.
- If the manifest is **absent** → unchanged: fall through to today's
  `Files.isDirectory(dataRoot/plans)` check.
- If the manifest is **present but unparseable / foreign** → ignore it, fall
  through to today's logic (no hard fail — keeps the "rare, not fragile"
  spirit).
- `LegacyDataTreeGuard` still wins (a `.agents/` tree is `Unresolvable`
  regardless of any manifest).
- `resolve()` stays **detection-only** — it must not write the manifest.

- Tests (`ProjectDataStoreResolverTest` / `resolver.rs`, both languages):
  1. populated `plans/`, **no** manifest → still `Settled(InRepo)` (grandfathers
     every existing repo) — this is the regression guard.
  2. manifest present, **no** `plans/` yet → `Settled(InRepo)` (new behaviour:
     the marker is proof).
  3. manifest present + populated `plans/` → `Settled(InRepo)` (agree).
  4. unparseable `manifest.toml` → falls through to current logic.
  5. `.agents/` legacy tree + a manifest → still `Unresolvable(LEGACY_AGENTS_TREE)`.
  6. separate-dir config entry + manifest at the external root → behaviour
     unchanged (config already wins; the manifest must not perturb it).

High: this is the one change that touches the resolution branch table, and the
backward-compat guarantee for existing corpora rides on getting the "absent →
unchanged" arm exactly right. De-risk by proving arms 1 + 2 first.

### Task 4: `store init` writes `manifest.toml` on every creation path [Medium]

*Depends-on: 1,2*

In `Init.act()` / `init.rs::act()`, after the directory is provisioned, write
the manifest at the data root:

- `EXTERNAL`, `RECREATE_MISSING_DIR` → `<chosen-dir>/manifest.toml`
- `IN_REPO` → `<repo>/.shipsmooth/manifest.toml`

Idempotent: overwrite an existing manifest with the current build's version (a
re-run or upgrade refreshes the stamp). Do it via the Task 1 accessor — mint a
locator for the just-created roots, or a small `Manifest::write_to(data_root)`
helper; do not hardcode `.shipsmooth/manifest.toml` in the command.

- Tests: `InitTest` / `init.rs` tests — each of the three `--type` paths leaves
  a well-formed `manifest.toml` in the right place carrying the build version;
  `store info --json` output is unchanged; a second `store init … recreate`
  rewrites it without error.
- Parity: `store/settled-same-repo` and `store/settled-separate-dir` scenarios
  now also produce a `manifest.toml` — Task 5 handles the harness.

### Task 5: parity harness — manifest capture + `cli-version` normalisation [Medium]

*Depends-on: 4*

- `exp/rust/parity/run.sh`: in `capture_scenario`, after a scenario that ran
  `store init`, copy the resulting `manifest.toml` into the capture dir with
  its `cli-version` line normalised to `<VERSION>` (a one-line `sed`, exactly
  like the existing `shipsmooth.toml` schema-version normalisation two lines
  above it). The rest of the file stays byte-checked.
- Add a `store/init-in-repo-manifest` (or fold into `settled-same-repo`) and a
  separate-dir equivalent so both data-root layouts are asserted.
- De-risk: reverting Task 4 must make the new capture diverge (one side writes
  a manifest, the other does not); with both sides done it is byte-identical
  modulo `cli-version`.
- Run `exp/rust/parity/run.sh` green and `cargo test --workspace` green.

Medium: shell-only, but the normalisation must be surgical or it hides a real
divergence.

---

## Verification (end to end)

- Java: `./gradlew :core:test :cli:test` green; the repo's coverage gate passes
  for the new code.
- Rust: `cd exp/rust && cargo test --workspace` green, `cargo clippy --workspace
  --all-targets` clean, `cargo llvm-cov` ≥95% on `manifest.rs` and the new
  resolver arms.
- `exp/rust/parity/run.sh` — all scenarios byte-identical (modulo the
  normalised `cli-version`).
- Manual smoke, both binaries, in a scratch git repo:
  - `store init --type separate-dir --path /tmp/x-ss` → `/tmp/x-ss/manifest.toml`
    exists with `[shipsmooth] kind = "state-store"` + the build's `cli-version`
    + `[manifest-schema] version = "1"`; `store info --json` still
    `status: "ready"`.
  - `store init --type same-repo` → `.shipsmooth/manifest.toml` likewise.
- Regression: this repo's own `.shipsmooth/` (100+ plan files, no manifest)
  still resolves `Settled` under both binaries — `shipsmooth store info --json`
  reports `status: "ready"` unchanged, and no manifest is written by the read.

## Out of scope

Repo-identity / origin-URL matching, per-store UUID, copied/moved-store
detection, schema-version migration, a `store repair` / heal command, and the
stricter "an unmarked `.shipsmooth/` is not settled" behaviour (needs the heal
story first).
