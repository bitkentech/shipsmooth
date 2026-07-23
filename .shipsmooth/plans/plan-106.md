# plan-106 — Rust port: the `store` command

## Context

Feature (in the user's words): *continue rust version* — scoped in conversation
to **"let's just do store command in this plan"**.

plan-102 returned a **GO** verdict on the Rust migration (2 MB binary, 3.8 MB RSS,
<10 ms startup, versus ~103 MB / ~69 MB / ~450 ms for the jlink-packaged Java CLI)
and recommended porting `gw` + `conf` next, then the cli packages. This plan takes
the first real vertical slice through that recommendation: **the `store` noun group,
end to end**, both leaves, behaviour identical to Java.

`store` is the right first slice because it is the command the skill calls before
anything else (`$SS store info --json` is how an agent finds `plansDir`), and
because it is self-contained: it needs the config/resolution chain but touches
neither the XML model's write path nor git beyond reading `origin`.

### What already exists (merged, `exp/rust/`)

A Cargo workspace at `exp/rust/` (self-contained Gradle build as of this branch —
not part of the main build):

- `ss-core::model` — XML model on the quick-xml event API; round-trips all 8
  Java-written golden fixtures byte-identically, including unknown `xs:any`
  elements.
- `ss-core::plan` — Slugs, PlanMarkdownParser, PlanSummaryFormatter, PlanNumbers,
  Stub. The Java `plan resume` transcript is a byte-exact golden for the formatter.
- `ss-cli` — skeleton binary plus `probe`; no real commands yet.
- `fixtures/` (regenerable via `generate.sh`) and `parity/run.sh`.

Coverage on the ported code measured at 95.5% total with `cargo llvm-cov`.

### Scope: the `store` dependency chain

`store` itself is thin — the work is the chain beneath it. Java main source in
scope (~1,320 lines):

| Java | Lines | Rust destination |
|---|---|---|
| `cli/conf/ds/ProjectDataStoreResolver` | 205 | `ss-cli::ds::resolver` |
| `cli/conf/ds/ConfigWriter` | 166 | `ss-cli::ds::config_writer` |
| `cli/conf/ds/DataStoreResolution` | 129 | `ss-cli::ds::resolution` |
| `cli/ResolutionJson` | 122 | `ss-cli::resolution_json` |
| `core/conf/ShipsmoothDataLocator` | 94 | `ss-core::conf::locator` |
| `cli/conf/ds/ProjectDataStore` | 91 | `ss-cli::ds::store` |
| `cli/conf/ds/StandaloneConfig` | 66 | `ss-cli::ds::config` |
| `core/conf/ResolvedStateRoot` | 50 | `ss-core::conf::state_root` |
| `cli/conf/ds/RemoteUrl` | 36 | `ss-cli::ds::remote_url` |
| `cli/conf/ds/LegacyDataTreeGuard` | 32 | `ss-cli::ds::legacy_guard` |
| `cli/store/{Store,Info,Init,StateReport}` | ~200 | `ss-cli::commands::store` |
| error types (`StateRootUnsettled`, `InaccessibleRoot`, `StandaloneConfigException`) | ~46 | `ss_core::Error` variants |

`cli/conf/ds/ArrayOfTablesTomlEmitter` (75 lines) is **deleted, not ported** —
`toml_edit` emits `[[projects]]` correctly, which is the entire reason that class
exists (plan-90).

**Not ported:** `AppComponents` / `ServicesModule` (Dagger). Per 01-core.md §conf
the `Provider<T>` indirection exists so the picocli command tree can be built
before the state root settles; clap parses before any service is constructed, so
the problem evaporates.

### Out of scope

- `gw` (GitState/GitTags/TaskStore) beyond the single `git remote get-url origin`
  read that `RemoteUrl` needs.
- Every other command (`plan *`, `task *`).
- Any shipping path: no release, no installer, no SKILL.md `cliBin` change. The
  Java CLI stays the daily driver and remains authoritative throughout.

### Contracts that must stay byte-identical

These are consumed by skills and hooks, so they are the definition of done:

1. **`store info --json` ready shape** — `{"status":"ready","storageType":…,
   "stateRoot":…,"plansDir":…}`, exactly the field names and order in
   `ResolutionJson.ready`.
2. **The resolution gate** — `needs-decision` / `unresolvable` JSON shapes and
   **exit codes 10 / 11**. The `prompt` string is rendered by the CLI and shown to
   the user verbatim by the skill, so its newlines and `Recommended —` / `Alternative —`
   labels are part of the contract.
3. **Wire tokens** — `separate-dir` / `same-repo` / `recreate`, which are what the
   skill passes back to `store init --type`.
4. **`shipsmooth.toml`** — read files written by either implementation; same key
   names and multi-line `[[projects]]` layout (plan-90).
5. **stdout/stderr discipline** — informational output to stdout, errors only to
   stderr.

### Design decisions

- **Path handling.** Java's `toAbsolutePath().normalize()` is *lexical*. Rust's
  `canonicalize()` resolves symlinks and fails on missing paths — using it would
  break config-entry matching for symlinked repos and for the not-yet-created
  external dir. Use a lexical normalize helper throughout; never `canonicalize`.
- **Config leniency (plan-87).** An empty or unparseable config resolves as "no
  usable config" and falls through to filesystem detection — it must never wedge
  as `Unresolvable(UNKNOWN)`. A failed `store init` write can leave a 0-byte config
  behind; that must not poison an otherwise-valid project. Port this deliberately,
  with the test that pins it.
- **Config-file location is injected.** Java takes a `ConfigFileLocator` so tests
  can redirect it. Rust does the same: every ported test points at a `tempfile`
  dir, so **no test in this plan touches the real `~/.config/shipsmooth/shipsmooth.toml`**.
  The shipped binary resolves the real path exactly as Java does — there is no
  artificial write restriction.
- **JSON is hand-built in Java** to avoid a startup dependency. Rust already has
  `serde_json` in the workspace; use it, but pin the output with byte-exact tests
  against the Java shapes rather than trusting serde's field ordering.

### Verification

Two independent signals, both required:

1. **Ported Java tests green** (~1,100 lines of JUnit → `#[test]`). Where a Java
   test asserts an exact output string, that string is the spec — copy it verbatim.
   Tests are ported *with* their package, never generated afterwards.
2. **Parity against the real Java binary** — extend `parity/run.sh` to run both
   implementations against identical fixture repos and diff stdout/stderr/exit code
   for `store info`, `store info --json`, and `store init` across the branch table
   (clean first run, settled same-repo, settled separate-dir, missing configured
   dir, malformed entry, legacy `.agents/` tree).

Coverage target: **95%** on net-new Rust (the plan-102 convention; ported code came
in at 97–100%).

### Settled questions

- **`store init`'s git-init of a new external state dir is in scope** (decided at
  calibration). It is part of `init`'s observable behaviour, so leaving it out would
  make the parity check on `init` meaningless. It lives in
  `ProjectDataStore.Standalone.init()` (Task 7), not in the `Init` command. This and
  `RemoteUrl`'s `git remote get-url origin` are the only two places this plan shells
  out to git; both use `std::process::Command` rather than `git2`, preserving user
  git config, hooks, and credential helpers exactly as the Java `ProcessBuilder` path
  does.

## Tasks

### Task 1: Extend the golden fixture corpus for store resolution [High]

*Depends-on: none*

Capture Java-CLI transcripts for every branch of the plan-85 resolution table
*before* porting anything, while the Java CLI is still the daily driver. For each
of: clean first run, settled same-repo, settled separate-dir, config dir missing,
malformed config entry (bad/missing `storageType`, `same-repo` carrying a
`storageRoot`), and a legacy `.agents/` tree — record `store info`, `store info
--json`, and the exit code, plus the `shipsmooth.toml` that produced it.

High risk because it is the *spec* everything downstream is checked against: a
fixture that captures the wrong thing silently validates a wrong port. Extend
`fixtures/generate.sh` so the corpus is regenerable, and drive it against temp
repos and a redirected config path, never the real one.

### Task 2: Resolution model and JSON contract [High]

*Depends-on: 1*

Port `DataStoreResolution` (the Settled/NeedsDecision/Unresolvable sum type, its
`Choice` / `UndecidableSituation` / `UnresolvableReason` enums and their messages),
`ProjectDataStore`, and `ResolutionJson`. Rust enums with data map onto Java's
sealed-interface-plus-records directly.

High risk: this is the skill-facing wire contract — the `prompt` rendering, the
stable tokens (`separate-dir` / `same-repo` / `recreate`), and the exit-code
mapping. Pin every JSON shape with byte-exact tests against the Task 1 transcripts.

### Task 3: Config read path — StandaloneConfig on toml_edit [High]

*Depends-on: 1*

Port `StandaloneConfig` and its parsing, including entry matching by the
`(localPath, remoteUrl)` pair with **lexical** path normalisation. Carry over the
plan-87 leniency: empty, 0-byte, or unparseable config → "no usable config", never
a hard failure.

High risk because it is where Java-vs-Rust semantics diverge most quietly —
lexical-vs-canonical paths, and Jackson-vs-toml_edit treatment of a malformed or
empty document. Port `TomlSchemaIntegrationTest` and
`MultiLineTomlConfigIntegrationTest` alongside.

### Task 4: ProjectDataStoreResolver branch table [High]

*Depends-on: 2, 3*

Port `ProjectDataStoreResolver` — config-entry classification, filesystem
fallback, `LegacyDataTreeGuard`, and the sibling-of-repo external path proposal
(`<parent>/<repo>-shipsmooth`, never hash-derived). Port
`ProjectDataStoreResolverTest` (310 lines) in full; it is the branch table's
executable spec.

High risk: this is the decision core of the whole slice, and every later task
depends on its classification being exactly right.

### Task 5: ShipsmoothDataLocator and ResolvedStateRoot [Medium]

*Depends-on: 2*

Port `ShipsmoothDataLocator` (the single source of path truth — the same-repo
`.shipsmooth/` vs separate-dir layout difference lives here and must not be
re-derived elsewhere) and the `ResolvedStateRoot` validation token, plus
`InaccessibleRootException` / `StateRootUnsettledException` as `ss_core::Error`
variants.

Medium: mechanical, but `plansDir()` is what the skill actually consumes, so an
error here is user-visible in every later command.

### Task 6: store info leaf [Medium]

*Depends-on: 4, 5*

Wire the clap subcommand: `store info` with `--json` / `-j`, `StateReport`'s ready
rendering (text and JSON), and repo-root/remote-url binding defaulting to CWD.
Port `InfoTest` (139 lines). All informational output to stdout.

Medium: thin over proven parts, but it is the first end-to-end command and the one
the skill calls most.

### Task 7: ConfigWriter and the store init leaf [Medium]

*Depends-on: 4, 5*

Port `ConfigWriter` on `toml_edit` — deleting `ArrayOfTablesTomlEmitter` rather
than transliterating it — preserving atomic write (write sibling `.tmp`, then
rename) and the multi-line `[[projects]]` layout from plan-90. Then the `init`
leaf: `--type separate-dir|same-repo|recreate`, directory creation, and its success
report. Port `ConfigWriterTest` (159) and `InitTest` (176).

Also port `ProjectDataStore.Standalone.init()` → `initStateRepoIfAbsent()`, which is
where the **git-init of a new external state dir** actually lives (not in `Init`):
its tiered check is load-bearing — return early if `<stateDir>/.git` is a directory
(no subprocess at all), else create the dir only if absent, then `git init` either
way, since the dir may exist without being a repo after an interrupted earlier init.
Shell out with `std::process::Command`, merging stderr into stdout as the Java
`redirectErrorStream(true)` does, and fail on a non-zero exit.

Medium: the write path is well-covered by ported tests and the config-file
location is injected, so no test touches the real config; the shipped binary
behaves exactly as Java does.

### Task 8: store parity harness [Medium]

*Depends-on: 6, 7*

Extend `parity/run.sh` to run the Java CLI and the Rust binary against identical
fixture repos and diff stdout, stderr, exit code, and resulting `shipsmooth.toml`
for both leaves across every Task 1 branch. Golden-baseline style, as in plan-79.

Medium: the independent check that ported tests can't provide — ported tests
inherit the porter's assumptions; this compares against the real binary.

### Task 9: Migration notes write-back [Low]

*Depends-on: 8*

Update `docs/rust-migration/02-cli.md` and `00-overview.md` with what the `store`
slice actually cost versus the estimate, any Java-vs-Rust divergences found, and
the recommended next slice. Record decisions that outlived the plan (toml_edit
over the hand-rolled emitter; lexical paths; Dagger dropped).
