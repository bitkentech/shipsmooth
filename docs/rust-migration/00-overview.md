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

## Store slice findings (plan-106, 2026-07-27) — first full command port

The `store` noun group, both leaves end to end, plus its whole dependency chain
(`ss-cli::ds`, `ss-cli::store`, `resolution_json`, `project`, `ss-core::conf`)
is ported and verified byte-identical to the Java CLI.

- **Cost.** ~1,320 lines of Java main source + ~1,100 lines of JUnit became
  ~3,180 lines of Rust (implementation plus the ported tests, which live inline
  as `#[cfg(test)]` modules in the same files) across 9 tasks over four days
  (2026-07-24 → 2026-07-27) — in line with the one-package-per-session
  estimate. Coverage 97–100% per file (`cargo llvm-cov`).
- **Parity.** `parity/run.sh` rebuilds all 10 plan-85 resolution branches at
  identical paths for each implementation and byte-diffs stdout, stderr, exit
  code, and the resulting `shipsmooth.toml`; all 10 pass. The two end-to-end
  tests written before any task code (`tests/store.rs`) assert byte-exact
  transcripts.
- **Divergences found (all resolved):**
  - `schema.location` in `shipsmooth.toml` embeds the *writing* CLI's own
    version, so the two implementations legitimately differ there. Decided:
    the Cargo workspace version is **not** synced to Java releases; the parity
    harness normalises the token to `v<VERSION>` and byte-checks the rest.
  - The Java resolver's `IOException → Unresolvable(UNKNOWN)` branch has no
    Rust equivalent: after the lenient config parse, no fallible operation
    remains (`is_dir` cannot error). The `UNKNOWN` reason stays in the enum
    for the wire contract.
  - `ConfigWriter::render` is infallible in Rust, so Java's exploding-emitter
    atomicity test became an unwritable-directory test (unix-gated).
  - Locator quirk preserved as-is: in-repo mode with a missing repo reports
    role "state" (a Java argument-evaluation-order artifact).
- **Decisions that outlived the plan:**
  - `toml_edit` replaces `ArrayOfTablesTomlEmitter` (deleted, not ported);
    multi-line `[[projects]]` is its default, and other projects' entries are
    preserved verbatim via a `DocumentMut` read-modify-write.
  - Lexical path normalisation (`ds::paths`) confirmed necessary —
    `canonicalize` would have broken config-entry matching in the fixture
    scenarios.
  - Dagger's `Provider<T>` indirection evaporated as predicted: a plain
    `ProjectContext { repo_root, remote_url }` passed at dispatch replaced the
    `bindStoreInit` reflection dance.
  - Jackson `FAIL_ON_UNKNOWN_PROPERTIES` parity = serde
    `deny_unknown_fields`; the plan-87 leniency (0-byte/unparseable config ==
    absent) is ported with the tests that pin it.
  - git shelling stays on `std::process::Command` with
    `redirectErrorStream(true)` semantics (stderr merged into stdout), for
    both `RemoteUrl` and the tiered `initStateRepoIfAbsent`.
- **Recommended next slice:** `ss-core::gw` (GitState, GitTags, TaskStore — a
  façade over the proven XML model), then the plan/task command leaves per
  02-cli.md. The store slice already exercises the resolve-gate shape, so the
  remaining `main.rs` wiring is incremental.

## gw slice findings (plan-107, 2026-08-19) — the XML gateway

`ss_core::gw` (`TaskStore`, `GitState`, `GitTags`) plus `gw::xml_time` and
`PlanMarkdown.sliceTaskSection` is ported and verified byte-identical to XML
the Java CLI wrote.

- **Cost.** ~540 lines of Java main source + ~360 lines of JUnit became ~1,850
  lines of Rust (implementation, inline `#[cfg(test)]` tests, and two
  integration tests) across 9 tasks. Elapsed 2026-08-06 → 2026-08-19, but the
  working time was four sessions, not two weeks. Coverage 99.35–99.75% region
  and 98–100% line per file (`cargo llvm-cov`), against a 95% target.
- **Parity.** `tests/gw_golden_replay.rs` replays `plan init` plus all
  seventeen mutations from `fixtures/generate.sh` through the Rust `TaskStore`
  and byte-diffs every intermediate file against what the Java binary wrote,
  persisting through the real write path. Timestamps are not normalised away:
  the clock is pinned per step to the value the Java run recorded, and steps
  that should record no timestamp run under a 1999 sentinel clock so a leak
  fails the diff. The harness was checked non-vacuous by perturbation — a
  one-millisecond timestamp change and a wrong `depends-on` each fail, naming
  the diverging step.
- **Divergences found (all resolved, all commented at the call site):**
  - `GitTags.versionNumber` throws `NumberFormatException` on a tag like
    `plan-7-v1-rc`, which its own `plan-{N}-v*` glob can match. The port
    returns `None` and derives `v1`, consistent with GitTags' everything-
    degrades contract.
  - `runExitCode`'s `redirectErrorStream(true)` interleaves stdout and stderr
    live; Rust's `output()` captures them separately and the port
    concatenates, so ordering can differ for a command writing to both.
  - `getDependsOn`/`addComment` return `Result` in Rust because Java's
    `findTask` throws for an unknown id; `getTaskName` stays total because
    Java's resolves through a stream default to the stringified id.
  - The preamble end-to-end test, written before any task code, assumed
    `generatePlanTasks` assigned task ids positionally. Java writes the
    parsed heading's id through verbatim; the test was corrected, not the
    implementation. **Lesson: a preamble test is a hypothesis about the port,
    not a specification of it** — when it disagrees with the Java source, the
    Java source wins.
  - `is_branch_pushed_and_not_ahead` is the one read query that emits a
    diagnostic, because Java probes the upstream with `runExitCode` rather
    than `runLines`. Faithful, and now pinned by a test.
- **Decisions that outlived the plan:**
  - **Injectable clock** (`Clock = Box<dyn Fn() -> OffsetDateTime>`, default
    `system_now`) as planned — it is what makes the golden replay byte-exact
    rather than timestamp-normalised.
  - **Injectable diagnostics sink** for `GitState`
    (`Diagnostics = Box<dyn Fn(&str)>`, default `eprintln!`) — *not* in the
    plan. git's exact stderr strings are contract here, and `eprintln!` cannot
    be asserted on in a Rust unit test. Expect to reuse this seam wherever a
    ported class writes diagnostics rather than returning them.
  - **Hand-rolled XSD lexical rendering** (`gw::xml_time`): no `time` format
    description produces `XMLGregorianCalendar`'s `Z`-for-zero-offset rule, so
    the writer is manual and the tests parse its output back with a crate
    format description as an independent check.
  - `system_now`'s UTC fallback is split into a `local_or_utc` seam, because
    `time` refuses to read the platform offset once the process is
    multi-threaded — so the fallback is reachable in production and was
    silently unexercised by tests until split out.
  - `parseTasksFromPlan` dropped as planned; callers use `plan::parse_tasks`.
- **Recommended next slice:** the `task` command leaves first (6 thin files
  straight over the `TaskStore` just ported), then `plan`. Note that `plan`'s
  leaves are *not* pure wiring: `NewPlan`, `PlanService`, `ScaffoldResult` and
  `ScaffoldException` are still unported (§2 of 01-core.md covers 6 of the
  package's 10 classes), and `NewPlan` is the scaffolding the `plan init` and
  `plan quick` leaves sit on. `gw` unblocked it — `NewPlan` needs `GitState`,
  which now exists — but it is core work to schedule, not command wiring.
  02-cli.md also flags a defect to port as-is rather than fix:
  `plan tag --kind version` derives the version from the XML field, not git
  tags.

## task slice findings (plan-108, 2026-08-20) — the first state-dependent commands

The `task` noun group (all five leaves) is ported and parity-verified, along
with the generic **resolve gate** every remaining state-dependent command will
dispatch through.

- **Cost.** ~290 lines of Java main source became ~295 lines of Rust
  implementation plus ~317 lines of integration tests, over 8 tasks in one
  session. Coverage: every `task/` file 100% except `add.rs` at 91.30% line;
  `ss-cli` total 96.29% line against the agreed 95% bar.
- **The gate was the real work, not the leaves.** The leaves are 13–34 lines
  each; `store`/`probe` are state-*independent*, so nothing before this slice
  had ever needed the classify-then-gate step 02-cli.md specifies. That is why
  the plan put it first and rated it High.
- **Parity.** `parity/run.sh` grew 13 `task` scenarios (23 total, all
  byte-identical). Because `plan init` is not ported yet, every scenario is
  seeded with the **Java** binary for both sides, so the starting XML is
  identical by construction and only the command under test varies.
- **The harness caught a real bug on its first run** — the invalid-status
  message was missing Java's `Error: ` prefix. The ported unit *and*
  integration tests both passed, because both asserted the porter's own wrong
  assumption. This is plan-107's lesson recurring from the other side: ported
  tests pin what you *believed* the Java did; only the real binary pins what it
  *does*. **Run the parity harness before believing a leaf is done.**
- **Divergences found (all resolved, all commented at the call site):**
  - **Java stack traces on unhandled error paths.** `deviation --type bogus`,
    an unknown task id, and an unknown plan all let the exception reach
    picocli's default handler, which dumps a full JVM stack trace to stderr;
    Rust prints one `shipsmooth: <msg>` line. Exit code (1), stdout, and the
    resulting XML are identical. Not reproducible and not a contract, so those
    three scenarios compare stderr only as "was a diagnostic emitted".
    `status --status bogus` is deliberately **not** on that allowlist: Java
    validates it explicitly, so it matches byte-for-byte — which is exactly
    what caught the missing prefix.
  - **`task status` exits 2, not 1** — the only leaf with its own exit code,
    because Java validates before calling the service. Preserved as-is.
  - **`set-commit --branch` is accepted and ignored.** Java's
    `PlanService.setTaskCommit` takes the argument and never passes it to
    `TaskStore.setCommit`. Ported as-is with a test pinning that it is inert;
    filed as behaviour to decide on later, not fixed in flight.
  - **Locale, not code:** under a non-UTF-8 locale the JVM transcodes the
    decision prompts' em dashes to `?` while Rust always writes UTF-8, failing
    4 *store* scenarios for purely environmental reasons. The harness now pins
    `C.UTF-8`. Worth knowing before trusting any future parity failure.
- **Decisions that outlived the plan:**
  - **No `PlanService` struct.** Every method the task leaves needed was a
    one-line load-mutate-save wrapper, so `TaskStore::mutate` carries it
    instead. `PlanService` proper (with `quickStart`/`NewPlan`) belongs to the
    `plan` slice.
  - **`GateOutcome` over `Option<i32>`.** The gate consumes the resolution and
    returns `Exit(code) | Proceed(store)`, so there is no unreachable `Settled`
    arm to guard after the fact — the shape Java's exception handler could not
    express.
  - **`TaskStatus::ALL`** added to the `xsd_enum!` macro so allowed-value
    messages derive from the enum rather than a hand-typed list.
  - **Guard your parity seeds.** Under `set -e` a failing seed command aborted
    the harness with no diagnostic, making the seed check unreachable; seeding
    now reports which step failed. An unnoticed bad seed compares two
    identically-broken runs — a false pass, the worst outcome for a harness.
- **Recommended next slice:** the `plan` leaves, but note it is **not** pure
  wiring: `NewPlan`, `PlanService`, `ScaffoldResult` and `ScaffoldException`
  are still unported and `NewPlan` is what `plan init`/`plan quick` sit on. The
  gate this slice built means the remaining leaves are dispatch-only work;
  budget the core scaffolding classes as the real cost. 02-cli.md's flagged
  defect still stands: `plan tag --kind version` derives the version from the
  XML field, not git tags — port as-is.

## plan slice findings (plan-109, 2026-08-20) — the CLI is feature-complete

The `plan` group (all nine leaves) plus the last unported `svc::plan` classes
are ported and parity-verified. **Every Java CLI package now has a Rust
equivalent**; there is no remaining command surface to port.

- **Cost.** ~657 lines of Java main source became ~1,617 lines of Rust
  (implementation, inline tests, 411 lines of integration tests, and 147 lines
  of parity harness) across 10 tasks in one session. Coverage: `ss-core`
  98.98% line with `markdown.rs` and `new_plan.rs` at 100%/99.24%; `ss-cli`
  96.17% line, every `plan/` file 100% except the leaves' unreachable error
  branches.
- **Parity.** 45 scenarios (10 store, 13 task, 22 plan), all byte-identical.
  Plan scenarios seed with the implementation **under test** rather than
  always with Java — possible for the first time because `plan init` is now
  ported, and strictly stronger: a `plan init` divergence surfaces in the
  seeded XML instead of hiding behind an identical Java-written start.
- **Divergences found (all resolved):**
  - **`plan tag`'s recorded defect does not exist.** 02-cli.md said it
    "derives the version from the XML field, not git tags" and should be
    ported as-is. The Java `Tag` contains zero XML references; it calls
    `GitTags.nextPlanVersion`, which reads `git tag -l … --sort=-version:refname`.
    Corrected in 02-cli.md rather than faithfully reproducing a bug that was
    not there. **Check a recorded defect against the source before porting it.**
  - **Rendered timestamps.** `plan show`/`resume` print the project-update
    timestamp, so the wall-clock divergence already normalised in the XML also
    reaches stdout. Caught by the harness, not by the ported tests.
  - **The plan group prints errors to stdout**, unlike `store`/`task` which
    use stderr — except `plan init`, which uses stderr. Ported as observed.
  - Same Java-stack-trace allowlist as the task slice for the paths picocli's
    default handler swallows.
- **Decisions that outlived the plan:**
  - **`PlanService` was never built**, extending plan-108's call. Its four
    remaining methods are one-liners over `NewPlan` and `TaskStore`. This
    deliberately contradicts 01-core.md's "keep, cli leaves call it", which
    predates the `TaskStore::mutate` seam that absorbed its reason to exist.
  - **`BranchOps` trait** as the scaffolding seam: Java's `NewPlanTest`
    subclasses `GitState` to stub two methods, and Rust has no subclassing.
    One test drives the production `impl` against a real repo so the fakes do
    not leave the shipped path unexercised.
  - **`LeafContext`** generalises plan-108's `dispatch_task`: the settled
    roots travel with the gateways because `plan quick` mints its own locator.
  - **Diagnostics and reports are returned, not printed** (`parse_with_diagnostics`,
    `near_miss_report`, `preflight::report`, `quick::handoff`). That is what
    makes exact wording, the 10-item cap, and the fail-fast/warn ordering
    unit-testable instead of only observable through the binary. `preflight`
    additionally builds its report once, because every line costs a git
    subprocess and one of them writes to stderr.
- **Next: the cutover, which is not a port.** With no command surface left,
  the remaining work is shipping — and 00-overview.md §risks already flags it
  as consequential: `packaging/`, `harness/*` and the Gradle publish tasks all
  assume a jlink runtime image per OS, so a Rust binary changes the artifact
  shape, the installer scripts, and SKILL.md's `cliBin` paths. Windows needs a
  real target and re-testing of the git-shelling paths. Plan that as its own
  slice before cutting any release from the Rust tree; the two implementations
  must be able to coexist during rollout (02-cli.md §definition of done).

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
