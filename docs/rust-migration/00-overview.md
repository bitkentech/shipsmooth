# Java → Rust migration: overview and feasibility

Scope: the `core` and `cli` Gradle modules (~3,800 lines of main-source Java in 63
files, plus a substantial JUnit test suite). Companion files:
[01-core.md](01-core.md) and [02-cli.md](02-cli.md) hold the per-package plans;
[03-dependencies.md](03-dependencies.md) has the Cargo.toml sketch and the full
Java-artifact → crate mapping.

## Verdict: yes, and it is a favourable case

This codebase is close to ideal for LLM-driven conversion:

- **Small and layered.** ~3.8k lines across two modules with a clean dependency
  direction (`cli → core`), no reflection-heavy business code, no concurrency
  beyond one retry loop, no networking.
- **Behaviour is pinned by tests.** The JUnit suite (unit + integration tests that
  drive the real CLI against temp git repos) is the executable spec. Ported tests
  plus a side-by-side parity harness give an objective "done" signal per package.
- **The hard Java dependencies map to *better* Rust equivalents.** JAXB's
  generated model becomes hand-written serde structs; Jackson-TOML's
  array-of-tables limitation (the reason `ArrayOfTablesTomlEmitter` exists)
  disappears entirely with `toml_edit`; Dagger's `Provider<T>` lazy-resolution
  dance is unnecessary once parsing and service construction are decoupled
  (which clap does naturally).
- **Packaging collapses.** The entire jlink/Semeru/shadow/module-info-reinjection
  machinery in `core/build.gradle.kts` and the cli image tasks exists only to
  ship a self-contained binary. `cargo build --release` produces one natively,
  per target, with none of it.

The interfaces that must stay **byte-compatible** (they are consumed by skills,
hooks, and existing user state on disk):

1. `plan-{N}-tasks.xml` files — must round-trip files written by the Java CLI,
   including unknown `xs:any` extension elements (`<depends-on>`).
2. `~/.config/shipsmooth/shipsmooth.toml` — read files written by either
   implementation; keep the same key names and `[[projects]]` layout.
3. The resolution-gate JSON on stdout + exit codes 10/11 — the SKILL.md contract.
4. stdout/stderr discipline (info → stdout, errors → stderr) and all exit codes.

## Exploration findings (plan-102, 2026-07-17) — verdict: GO

The starting sequence through the risk spike ran as plan-102 on
`t/102-rust-migration-explore`; the go/no-go question is answered **GO**:

- The XML model (quick-xml **event API**, `exp/rust/crates/ss-core/src/model/`)
  round-trips all 8 Java-written golden fixtures **byte-identically** —
  including two real plans (96/97), every enum value, escaped/unicode text,
  and unknown `xs:any` elements. The one hand-edited fixture normalizes
  idempotently, exactly matching JAXB's own re-indent behaviour.
- serde derive was confirmed unsuitable for the model (ordered `xs:any`
  capture, `<x></x>` vs `<x/>`, exact JAXB layout) — settled design in
  01-core.md §1.
- The warm-up port (`ss_core::plan`) validated the ported-tests approach; the
  Java `plan resume` transcript doubles as a byte-exact golden for the
  summary formatter.
- Fixtures + transcripts live in `exp/rust/fixtures/` (regenerable via
  `generate.sh`); coverage measured with `cargo llvm-cov` at 95.5% total
  (ported plan code 97–100%, net-new model ~95–96%).
- Recommended follow-up plan: port `gw` (GitState/GitTags/TaskStore — now a
  thin façade over the proven model) and `conf`, then the cli packages per
  02-cli.md.

## Target layout

Cargo workspace mirroring the Gradle module split:

```
Cargo.toml                 # [workspace]
crates/
  ss-core/                 # = gradle :core  (lib crate)
    src/
      model/               # replaces generated io.bitken.ss.jaxb (from plan-tasks.xsd)
      plan/                # io.bitken.ss.svc.plan
      gw/                  # io.bitken.ss.gw
      conf/                # io.bitken.ss.conf
      build.rs             # replaces java-templates Build.java (version, experimental flag)
  ss-cli/                  # = gradle :cli   (bin crate `shipsmooth`)
    src/
      commands/{plan,task,store}/
      conf/                # ExperimentalModeParser, ConfigFileLocator
      ds/                  # io.bitken.ss.cli.conf.ds (resolver, config, writer)
      resolution_json.rs
      main.rs
```

## Dependency mapping

| Java | Rust | Notes |
|---|---|---|
| JAXB + xjc-generated `io.bitken.ss.jaxb` | `quick-xml` + `serde` (hand-written structs) | Generated code is replaced by ~200 lines of explicit structs; see 01-core.md §model |
| Jackson `TomlMapper` (read) + `ArrayOfTablesTomlEmitter` (write) | `toml` / `toml_edit` | One crate does both directions correctly; the hand-rolled emitter is deleted |
| Jackson JSON (`ResolutionJson`) | `serde_json` | Keep field names/shape byte-identical |
| picocli | `clap` (derive) | Subcommands, hidden flags (`hide = true`), `--help`/`--version` built in |
| Dagger + `jakarta.inject.Provider` | plain structs + late construction | The Provider indirection exists so the command tree builds before the state root settles; with clap, parse happens before any service is constructed, so the problem evaporates. `OnceCell` only if genuine laziness is still wanted |
| `ProcessBuilder` git shelling | `std::process::Command` | Keep shelling out (do **not** switch to `git2`) — preserves exact behaviour incl. user git config, hooks, credential helpers |
| `XMLGregorianCalendar`, `LocalDate`, `OffsetDateTime` | `time` or `chrono` | Must emit XSD lexical forms (`2026-07-17`, `2026-07-17T10:00:00+05:30`) |
| `Files.move(..., ATOMIC_MOVE)` | `std::fs::rename` after writing sibling `.tmp` | Same-filesystem rename is atomic on POSIX and NTFS |
| JPMS `module-info`, jlink, shadow | — | Deleted. Cargo replaces all of it |
| java-templates `Build.java` | `build.rs` + `env!()` | `VERSION` from `CARGO_PKG_VERSION` or an injected env var; `EXPERIMENTAL_BUILD` as a cargo feature or compile-time env |
| JUnit 5 | `#[test]` + `assert_cmd`/`tempfile` | Integration tests spawn the built binary against temp git repos, same as today |

## How the LLM does the conversion (workflow)

Per-file transliteration is the wrong granularity; per-package conversion in
dependency order is right. Each step is one focused session: feed the model the
package's Java sources **and its tests**, plus the already-converted Rust crates
it depends on, and require `cargo test` green before moving on.

### Starting sequence

The granularity is a package plus its tests: a single file usually can't
compile or be tested alone (TaskStore needs the model; NewPlan needs
PlanNumbers and GitState), while a whole module is too big for one verifiable
step — if 15 tests fail across 4 packages, nothing points at which translation
choice broke what. A package is the smallest unit that compiles, runs its
tests, and localises a failure to one conversion session.

0. **Scaffold the walking skeleton** (half a day) — Cargo workspace, both
   crates, the `ss_core::Error` enum, CI running `cargo test`, and a skeleton
   parity script. Every later session then ends with a green check instead of
   an integration surprise at the end.
0b. **Capture golden fixtures from the Java CLI — now, while it is the daily
   driver.** Generate a corpus of `plan-{N}-tasks.xml` files exercising every
   feature (comments, deviations, depends-on, *unknown* extension elements),
   plus recorded stdout/exit-code transcripts for each subcommand, and commit
   them. Every fixture captured today makes the Rust side verifiable later,
   and it is far cheaper to produce while the Java build is still in use.
1. **Warm-up slice: pure plan logic** (`ss-core::plan`) — Slugs,
   PlanMarkdownParser, PlanSummaryFormatter, PlanNumbers, Stub, with their
   tests. Zero I/O beyond directory listing; done in a session. The point is
   not the code (it is trivial) — it is establishing the conventions every
   later session copies: error style, test layout, regex-flag translation,
   format strings ported verbatim. A cheap place to make and fix style
   mistakes.
2. **Risk spike: XML model + round-trip** (`ss-core::model`) — structs for
   plan-tasks.xsd, validated against the step-0b fixtures: read fixture →
   write back → diff empty. This is the one part of the migration that could
   genuinely fail (the `xs:any`/`<depends-on>` preservation and JAXB output
   formatting, see §risks), so it comes *before* anything is built downstream
   of the model. If quick-xml/serde can't round-trip cleanly and the model
   needs the hand-written event API, that finding must land in week one — it
   is the discovery most likely to change the plan.
3. **Gateways** (`ss-core::gw`) — GitState, GitTags, TaskStore. TaskStore is
   easy by this point: it is a façade over the model just proven in step 2.
4. **Config/locator** (`ss-core::conf`) — ShipsmoothDataLocator, ResolvedStateRoot,
   error types; Dagger module is *not* ported (see 01-core.md §conf).
5. **Data-store resolution** (`ss-cli::ds`) — StandaloneConfig, resolver,
   ConfigWriter on `toml_edit`; port the branch-table tests.
6. **Commands** (`ss-cli::commands`) — clap tree for plan/task/store leaves.
7. **Wiring** (`ss-cli::main`) — repo-root detection, resolution gate,
   ResolutionJson, exit codes; port the CLI integration tests.
8. **Parity harness** — script that runs the Java jar and the Rust binary against
   identical fixture repos (from step 0b) and diffs stdout/stderr/exit
   code/resulting state files for every subcommand. Golden-baseline style (as
   in plan-79).

Rules for every session:

- Tests are converted **with** their package, never generated after the fact.
  The JUnit suite is the only precise spec of intended behaviour; tests
  generated from freshly-converted Rust would assert whatever the conversion
  did — including its mistakes — a tautology, not a safety net. A ported test
  failing points at exactly where the translation diverged. Where a Java test
  asserts on exact output strings, the string is the spec — copy it verbatim.
- The Java comments are load-bearing (they encode plan history and defect
  rationale, e.g. the 0-byte-config tolerance from plan-87, the atomic-move
  read-retry). Carry the *why* comments into the Rust code; drop the
  Java-mechanics ones.
- No behaviour "improvements" during conversion. File an issue instead.

Rough effort: 3.8k lines Java → ~3–4k lines Rust; at one package per session
this is on the order of 8–10 sessions plus the parity harness.

## Risks and gotchas

- **XML output formatting.** JAXB's `JAXB_FORMATTED_OUTPUT` pretty-print
  (4-space indent, `<?xml ... standalone="yes"?>` header) differs from
  quick-xml's default. Since the files live in git-tracked or user-visible
  state dirs, gratuitous reformatting creates noisy diffs. Match JAXB's layout
  in the writer, or accept a one-time reformat and say so in release notes.
- **`xs:any` extension elements** (`<depends-on>`, future ad-hoc metadata
  fields) must round-trip *unmodified*, including elements the Rust structs
  don't know about. Model them as a `Vec` of raw preserved elements.
- **Leniency parity.** JAXB unmarshalling does **not** validate against the XSD
  by default (no schema set on the Unmarshaller) — it tolerates pattern/enum
  violations in surprising ways. The Rust reader should be equally lenient on
  read (error only on structural failure) and strict on write.
- **Path semantics.** Java's `toAbsolutePath().normalize()` is *lexical* — it
  does not resolve symlinks. Rust's `canonicalize()` does, and fails on
  missing paths. Use a lexical normalize helper (or the `path-clean` crate),
  never `canonicalize`, or config-entry matching breaks for symlinked repos.
- **Help-text drift.** clap's `--help` rendering differs from picocli's. The
  machine contracts (JSON gate, exit codes) must be identical; human help text
  may drift, but check SKILL.md / harness prose for anything that quotes it.
- **Release pipeline (out of scope but consequential).** `packaging/`,
  `harness/*`, and the Gradle publish tasks assume a jlink runtime image per
  OS. A Rust binary changes the artifact shape (single static executable,
  cross-compiled per target via `cargo` or `cross`), the installer scripts,
  and the SKILL.md `cliBin` paths. Plan that as its own follow-up before
  cutting any release from the Rust tree.
- **Windows.** Today's Windows story ships a jlink runtime + `.cmd` shim. Rust
  needs an `x86_64-pc-windows-msvc` (or `-gnu`) build in CI and re-testing of
  the git-shelling paths (`Command` quoting differs from `ProcessBuilder`).
