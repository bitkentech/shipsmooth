# cli module → `crates/ss-cli` (bin crate `shipsmooth`)

Java module `io.bitken.ss.cli`: 6 packages. Becomes the binary crate; depends
on `ss-core`. Conversion order: ds → conf → commands (store, then plan/task) →
wiring/main. The picocli→clap shift is the one *architectural* change in the
whole migration, so it is described first.

> **Status (plan-109, 2026-08-20): the CLI is feature-complete.** Every
> package in this module is **ported and parity-verified** — `ds/`, `conf`,
> `resolution_json.rs`, RepoRoot/RemoteUrl, `store` (plan-106), `ss-core::gw`
> (plan-107), `task` + the resolve gate (plan-108), and `plan` + the last
> `svc::plan` classes (plan-109). 45 parity scenarios byte-identical.
> Nothing remains to port; the next work is the **cutover** (packaging,
> installers, Windows, SKILL.md `cliBin`) — see 00-overview.md §plan-slice.

## Architecture shift: picocli command objects → clap + dispatch

Today each leaf (`plan init`, `task add-comment`, …) is a `Callable<Integer>`
class holding injected `Provider<Service>`s; `CommandTree` assembles specs from
the objects; `Shipsmooth.execute()` installs an exception handler that converts
`StateRootUnsettledException` into the resolution-gate JSON.

In Rust:

```rust
#[derive(Parser)]
#[command(name = "shipsmooth", version = VERSION, ...)]
struct Cli {
    /// Enable experimental subcommands.
    #[arg(long = "--enable-experimental", global = true,
          hide = !cfg!(feature = "experimental"))]   // Build.EXPERIMENTAL_BUILD
    enable_experimental: bool,
    #[command(subcommand)]
    cmd: Cmd,
}
enum Cmd { Plan(PlanCmd), Task(TaskCmd), Store(StoreCmd) }
```

- **Experimental gating.** Java filters experimental commands out of the tree
  at construction. In clap, either build the tree dynamically (builder API) or
  keep derive and reject experimental commands at dispatch when the mode is
  off + `hide` them from help. Match today's observable behaviour: hidden from
  `--help` AND unknown when invoked without the flag. (Currently no leaf is
  experimental — plan-97 removed the dead guards — so this may reduce to just
  the hidden `--enable-experimental` flag; verify before building machinery.)
- **The resolve gate.** Java relies on lazy `Provider.get()` throwing mid-call.
  Rust inverts it: after parsing, classify the matched command as
  state-dependent or state-independent (`store *`, `--help`, `--version` are
  independent). If state-dependent and resolution is not `Settled`, emit the
  gate JSON and exit 10/11 — *before* constructing services. Same observable
  contract, no exception plumbing. Keep the "settled resolution never gates"
  invariant as a unit test instead of the unreachable-branch IllegalStateException.
- **Exit codes.** `EXIT_NEEDS_DECISION = 10`, `EXIT_UNRESOLVABLE = 11`,
  config/init IO errors → 1 with the exact `shipsmooth: …` stderr strings from
  `Shipsmooth.main`. Use `std::process::exit(code)` from a thin `main` around
  a `run() -> i32`.

## Package-by-package

### `io.bitken.ss.cli` (root: Shipsmooth, CommandTree, RepoRoot, ResolutionJson, HasSpec) → `main.rs` + `resolution_json.rs`

- `Shipsmooth.main` → `main.rs`: repo-root detection (`RepoRoot` shells
  `git rev-parse --show-toplevel`, falling back to CWD — port from
  cli/RepoRoot.java with its test), `RemoteUrl` lookup, `ExperimentalModeParser`,
  single `ProjectDataStoreResolver.resolve()` per invocation, then the
  settled/unsettled branch. The `bindStoreInit` reflection dance (walking
  `ParseResult` to hand `Init`/`Info` their context) disappears: in Rust the
  dispatch match simply passes `(repo_root, remote_url, resolution)` to the
  store handlers as arguments.
- `CommandTree` → the clap derive tree above; ~90 lines of spec-building
  become attributes.
- `HasSpec` → not needed (no user objects).
- `ResolutionJson` → `resolution_json.rs` with serde_json. **Byte-compatible
  output is the requirement** — the skill parses this JSON. Port
  `ResolutionJsonTest` first and treat its expected strings as golden. Use
  explicit `#[serde(rename)]`s and a stable field order (serde_json preserves
  struct order; verify pretty vs compact matches Java's output).

### `io.bitken.ss.cli.conf` (ConfigFileLocator, DefaultConfigFileLocator, ExperimentalModeParser) → `conf/`

- `ConfigFileLocator` interface + default impl (XDG config dir →
  `~/.config/shipsmooth/shipsmooth.toml`) → a small trait (tests substitute a
  temp-dir locator) or just a `config_file_path(override: Option<&Path>)` fn;
  the trait exists purely for tests, so pick whichever the ported tests need.
  Use `std::env::var("XDG_CONFIG_HOME")` + home-dir fallback exactly as Java
  does — do not swap in the `dirs` crate's platform-native locations on
  macOS/Windows without checking what the Java code resolves there today
  (Java's logic is what existing user configs were written under).
- `ExperimentalModeParser.fromArgs` → scan argv for `--enable-experimental`
  before clap runs (it is needed to decide tree shape/gating, same as Java
  parses it pre-picocli).

### `io.bitken.ss.cli.conf.ds` (9 files) → `ds/` — PORTED (plan-106)

The richest package; port with its full test suite (branch table from plan-85).
Ported as planned; notable deltas: `resolver` has no `IOException → UNKNOWN`
branch (no fallible ops remain after the lenient parse), and `ConfigWriter`
preserves other projects' entries verbatim via a `toml_edit::DocumentMut`
read-modify-write rather than re-emitting.

- `StandaloneConfig` + `ProjectEntry` + `TomlSchemaRef` → serde structs. Keep
  key names exactly: `remoteUrl`, `localPath`, `storageRoot`, `storageType`,
  `[toml-schema]` table, `[[projects]]`.
- `ProjectDataStoreResolver` → direct translation of the classification logic
  (config entry match by `(localPath, remoteUrl)` → storageType branch table →
  filesystem fallback → clean-first-run proposal). Preserve the documented
  tolerances: unparseable/empty config == absent (plan-87 0-byte-config
  poisoning), lexical path normalization (see overview §risks), sibling
  external-path proposal `<parent>/<repo>-shipsmooth`.
- `DataStoreResolution` (sealed interface: Settled/NeedsDecision/Unresolvable +
  Choice/Situation/Reason enums) → a Rust enum — this is the one place the
  Rust version is strictly more natural than the Java (sealed-interface
  pattern-matching maps 1:1 onto `match`).
- `ProjectDataStore` (InRepo/Standalone with `init()` tiered state-repo
  creation) → enum + impl; `init()` keeps the plan-84 tiered
  initStateRepoIfAbsent semantics.
- `ConfigWriter` + `ArrayOfTablesTomlEmitter` → replaced by `toml_edit`
  serialization (multi-line `[[projects]]` is its default). **Delete the
  emitter**; keep ConfigWriter's atomic write-and-rename and its
  read-modify-write merge logic. Add one golden test: emit a two-project
  config and compare against a Java-written fixture to lock formatting.
- `RemoteUrl` → `git remote get-url origin` via Command (or config read —
  match whatever the Java does, same swallow-on-failure behaviour).
- `LegacyDataTreeGuard` → trivial port (detects pre-rename `.agents/` trees →
  Unresolvable LEGACY_AGENTS_TREE).
- `StandaloneConfigException` → `Error` variant.

The cli test-only `org.tomlschema` package (schema validation used by tests):
check whether any *product* behaviour depends on it; if it is test scaffolding
for config fixtures, port only what the surviving tests need — or replace with
`taplo`/plain asserts.

### `io.bitken.ss.cli.store` (Store, Init, Info, StateReport) → `commands/store/` — PORTED (plan-106, as `src/store/`)

- `store` group parent → clap subcommand enum.
- `Init` (158 lines, the biggest command): consumes the pre-computed
  `DataStoreResolution`, prompts/accepts a choice, writes the config entry,
  creates dirs / state repo. Its `bind(...)` method disappears (args passed at
  dispatch). Port `InitTest` + the first-run handshake behaviour (plan-85):
  CLI prompt field, sibling external path default.
- `Info` + `StateReport`: report resolution state. **stdout for info, stderr
  only for errors** — this is an explicit project rule; port the tests that
  pin it.

### `io.bitken.ss.cli.plan` (9 files) → `src/plan/` — PORTED (plan-109)

Plan, Init, Show, Tag, Branch, Preflight, Resume, QuickStart, ProjectUpdate.
Ported with their tests; notable deltas:

- **No `PlanService`** — the leaves call `NewPlan` and `TaskStore` directly,
  extending plan-108's decision (see 01-core.md §2 for why that guidance is
  superseded).
- **Errors go to stdout** for this group, unlike `store`/`task` — except
  `init`, which uses stderr. Ported as observed, not as expected.
- `init` reports near-miss diagnostics on stderr when it fails (zero tasks,
  nothing written) and on stdout when it succeeds, capped at 10 with an
  "… and N more" summary.
- `preflight` builds its report once rather than per-line: each line costs a
  git subprocess and `isBranchPushedAndNotAhead` writes a diagnostic to
  stderr (plan-107), so evaluating twice would double it.
- `branch`'s exactly-one-of `--issue`/`--plan` is enforced at dispatch with
  Java's own message, not a clap arg group whose wording would differ.
- `project-update`'s `--blocked` is tri-state in Java (arity-0 `Boolean`:
  true when present, null when absent) and maps to `Option<bool>`.

**Correction (plan-109):** this file previously recorded a defect here —
"`plan tag --kind version` derives the version from the XML field, not git
tags" — to be ported as-is. **That was wrong.** `Tag.java` contains no XML
references at all; it calls `GitTags.nextPlanVersion`, which derives from
`git tag -l 'plan-N-v*' --sort=-version:refname`. There was no defect to
preserve, and the Rust port matches the real behaviour. Verify a recorded
defect against the source before reproducing it.

### `io.bitken.ss.cli.task` (6 files) → `src/task/` — PORTED (plan-108)

Task group + AddTask, AddComment, AddDeviation, SetCommit, UpdateStatus. Ported
as planned; the leaves are 13–34 lines each over `TaskStore`. Notable deltas:

- No `PlanService` struct — `TaskStore::mutate` (load-apply-save) carries what
  the leaves needed; `PlanService` proper waits for the `plan` slice.
- `UpdateStatus` is the one leaf with its own exit code (**2**, not 1) and its
  own message, `Error: invalid status "X". Allowed values: …` — validated
  before the store is touched, as in Java. The `Error: ` prefix was missed on
  the first pass and caught by the parity harness, not by the ported tests.
- `SetCommit --branch` is accepted and ignored, matching Java's
  `PlanService.setTaskCommit`; pinned by a test rather than silently fixed.
- Java's *unhandled* error paths dump a JVM stack trace via picocli's default
  handler where Rust prints one line — an accepted, allowlisted divergence
  (exit code, stdout and XML all match). See 00-overview.md §task-slice.

### The resolve gate in `main.rs` — PORTED (plan-108)

Implemented as specified above, with one refinement: `gate` **consumes** the
resolution and returns `GateOutcome::Exit(code) | GateOutcome::Proceed(store)`,
so a settled resolution flows straight into service construction with no
unreachable arm to guard. `store`/`probe` remain state-independent and
dispatch unconditionally. `task` is the first family to route through it; the
`plan` leaves will reuse it unchanged.

### `java-templates/SchemaConfig.java` → constant or `build.rs`

Templated schema version/location constants — same treatment as core's
Build.java: compile-time constants from Cargo metadata.

## Test porting map

| Java test | Rust location |
|---|---|
| `ShipsmoothTest`, `CommandsTest`, `OptionMetadataTest`, `GroupedCommandTreeTest` | unit tests on the clap tree (`Cli::command().debug_assert()`, help/metadata asserts) |
| `ShipsmoothIntegrationTest`, `HelpAcrossTreeIntegrationTest`, `UnsettledTreeIntegrationTest`, `ShipsmoothGateTest` | `tests/` with `assert_cmd`: spawn the real binary in temp git repos; gate JSON + exit-code asserts |
| `Plan*Test` | `tests/` per command group |
| `AddTaskIntegrationTest`, `PlanServiceTest`'s task-mutation cases | `tests/task.rs` (ported, plan-108) |
| `DefaultConfigFileLocatorTest`, resolver branch-table tests | unit tests in `ds/` with temp XDG dirs |
| `InitTest`, `InfoTest` | `tests/store.rs` |

## Definition of done for the module

1. All ported tests green.
2. Parity harness (overview §workflow step 8) shows identical
   stdout/exit-code/state-file results for every subcommand on shared fixtures,
   with an explicit allowlist for accepted diffs (help text wording, XML
   whitespace if a one-time reformat is accepted).
3. A Java-written state tree (`.shipsmooth/` + external state repo +
   `shipsmooth.toml`) is fully usable by the Rust binary and vice versa —
   the two implementations must be able to coexist during rollout.
