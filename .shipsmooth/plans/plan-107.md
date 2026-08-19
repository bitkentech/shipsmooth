# plan-107 — Rust port: `ss-core::gw` (GitState, GitTags, TaskStore)

## Context

Feature (in the user's words): *Rust port of ss-core gw module* — the next slice
recommended by plan-106's closeout (00-overview.md §store-slice findings):
**"port `ss-core::gw` (GitState, GitTags, TaskStore — a façade over the proven
XML model), then the plan/task command leaves per 02-cli.md."**

plan-106 ported the full `store` command chain and parity-verified it
byte-identical against the Java CLI. `gw` is the layer beneath every remaining
command: the plan/task leaves (`plan init/resume/tag/branch/preflight`,
`task status/comment/...`) are thin wrappers over exactly these three classes.
Porting `gw` now completes `ss-core`'s hard part and unblocks the leaves as the
following slice.

### What already exists (merged, `exp/rust/`)

- `ss-core::model` — XML model on the quick-xml event API; round-trips all
  Java-written golden fixtures byte-identically, including unknown `xs:any`
  elements (`raw.rs` tree + `layout.rs` JAXB-layout writer + `enums.rs` typed
  accessors over lexical strings).
- `ss-core::plan` — Slugs, PlanMarkdownParser (`markdown.rs::parse_tasks`),
  PlanSummaryFormatter, PlanNumbers, Stub.
- `ss-core::conf` — ShipsmoothDataLocator, ResolvedStateRoot (plan-106).
- `ss-cli` — the full `store` noun group, `ds/` resolution chain,
  `resolution_json`, `project` context (plan-106).
- `fixtures/` (regenerable via `generate.sh`) and `parity/run.sh` (10 store
  resolution branches, all passing).

### Scope: the three `gw` classes plus one pulled-in dependency

Java main source in scope (~540 lines):

| Java | Lines | Rust destination |
|---|---|---|
| `core/gw/TaskStore` | 299 | `ss-core::gw::task_store` |
| `core/gw/GitTags` | 103 | `ss-core::gw::git_tags` |
| `core/gw/GitState` | 100 | `ss-core::gw::git_state` |
| `core/svc/plan/PlanMarkdown` (sliceTaskSection) | ~40 | `ss-core::plan::markdown` (extend) |

`PlanMarkdown` is in scope because `TaskStore.sliceTaskMarkdown` delegates to
it and it was not part of the plan-102 warm-up (only the parser was ported).

Java tests in scope (~360 lines): `GitStateTest` (155), `GitTagsIntegrationTest`
(103), `TaskStoreTest` (100), plus whatever pins `PlanMarkdown.sliceTaskSection`.

**Dropped, not ported:** `TaskStore.parseTasksFromPlan` — deprecated
pass-through to `PlanMarkdownParser`; its only caller is `TaskStoreTest` itself
(verified — no cli caller). Callers use the parser directly, per 01-core.md §3.
The Rust port of its test asserts against `plan::parse_tasks` instead.

### Out of scope

- `NewPlan`, `PlanService`, `ScaffoldResult/Exception` — they sit *above* gw
  and belong with the plan/task command-leaves slice (next plan), where their
  cli consumers are ported alongside.
- All plan/task CLI leaves and `main.rs` gate wiring (02-cli.md remainder).
- Any shipping path: no release, no installer, no SKILL.md `cliBin` change.
  The Java CLI stays the daily driver and remains authoritative throughout.

### Contracts that must stay byte-identical

1. **`plan-{N}-tasks.xml` files written by TaskStore** — a Rust-written file
   must be byte-identical to what Java writes for the same logical operations
   (modulo timestamps, see Verification): JAXB layout via the existing
   `layout.rs` writer, fresh-task element shape (`pending`, empty `commit`,
   `created-from`, empty `<comments/>`/`<deviations/>` containers exactly as
   JAXB renders them), `<depends-on>` as an `xs:any` extension element.
2. **XSD lexical timestamp forms** — `created` dates (`2026-08-06`) and update
   /comment/deviation timestamps (`2026-07-23T11:42:43.972+05:30` — millisecond
   precision, local UTC offset with colon) exactly as Java's
   `XMLGregorianCalendar` renders them; existing fixtures are the spec.
3. **Graceful git degradation** — `runLines` swallows spawn errors → empty
   output (`isClean()` treats "git unavailable" as clean; `GitTags` falls back
   to `plan-{N}-v1`). Tests depend on this; keep it.
4. **stderr diagnostics from git failures** — `runExitCode`'s exact strings
   (`<cmd> failed (exit N): <merged output>`, `<cmd> could not run: <msg>`);
   `GitStateTest` asserts on them. Informational output stays off stderr.
5. **Atomic XML write** — write sibling `.tmp`, rename over target, delete tmp
   on failure; and the reader's 5×100 ms retry loop that papers over the
   documented rename race. Port both verbatim.

### Design decisions

- **git stays on `std::process::Command`** (never `git2`), per the settled
  migration rule — preserves user git config, hooks, credential helpers.
  `runExitCode`'s `redirectErrorStream(true)` → merge stderr into stdout, as
  already done for `initStateRepoIfAbsent` in plan-106.
- **All git commands run in the configured `workDir`, never inherited CWD.**
  The plan-70 lesson lives in the GitTags class comment — carry it over.
- **GitTags keeps git's own version sort** (`git tag -l 'plan-{N}-v*'
  --sort=-version:refname`, first line wins) and the load-bearing distinction
  between "no tag" (→ `nextPlanVersion` = v1) and an existing v1 (→ v2).
- **TaskStore mutates the lexical-string model directly.** No ObjectFactory,
  no BigInteger, no DatatypeFactory — the class shrinks by roughly half, per
  01-core.md. New elements are built so `layout.rs` renders them identically
  to JAXB's output; the golden fixtures decide, not intuition.
- **`get/setDependsOn` work on the model's preserved raw-element list** (the
  `xs:any` mechanism proven in plan-102), replacing the Java DOM walk.
  Same semantics: set replaces, blank removes, get returns trimmed text or "".
- **Timestamp generation is injectable.** Java calls `OffsetDateTime.now()`
  inline; the Rust port takes a clock function (defaulting to now) so mutation
  tests and the golden-replay harness can pin exact timestamps instead of
  regex-normalising everything. The shipped default behaves exactly as Java.
- **`parseDependsOn` malformed-entry handling** — skip with the exact
  `parseDependsOn: skipping malformed entry '…' in depends-on: …` stderr line.

### Verification

Two independent signals, both required:

1. **Ported Java tests green** (~360 lines of JUnit → `#[test]`). Git tests run
   against real temp git repos (`git init` + config + one commit), same as the
   Java versions. Exact stderr strings are the spec — copy them verbatim.
2. **Golden replay against Java-CLI-written fixtures** — extend
   `fixtures/generate.sh` with a scripted Java CLI mutation sequence (plan
   init → add task → status transitions → comment → deviation → set-commit →
   depends-on set/change/remove → project update, capturing the XML after each
   step). A Rust integration test replays the equivalent `TaskStore` calls with
   a pinned clock and byte-diffs each intermediate file, normalising only the
   timestamp text captured from the Java run.

Coverage target: **95%** on net-new Rust (the plan-102 convention); ported code
has been landing at 97–100%.

## Tasks

### Task 1: Golden mutation-fixture corpus for gw [High]

*Depends-on: none*

Extend `fixtures/generate.sh` with a scripted Java-CLI sequence exercising every
TaskStore mutation (generate, add-task, each status transition, comment,
deviation, set-commit, depends-on set/replace/remove, project update with and
without status/blocked), capturing the XML after each step into
`fixtures/xml/gw/`. Drive it against temp repos and a redirected config path,
never the real state.

High risk because it is the *spec* everything downstream is checked against: a
fixture that captures the wrong thing silently validates a wrong port. Capture
now, while the Java CLI is still the daily driver.

### Task 2: TaskStore read/write path [High]

*Depends-on: 1*

Port `readPlanTasks` (5×100 ms retry loop, verbatim, with the race-rationale
comment) and `writePlanTasks` (mkdirs, sibling `.tmp`, atomic rename,
tmp cleanup on failure), plus the locator-backed `planTasksFile` /
`planTasksFileExists` / `loadPlan` / `savePlan` conveniences. Read-modify-write
of every existing fixture must stay byte-identical.

High risk: this is where a layout or `xs:any` regression would corrupt real
user task files; the atomic-write/retry semantics are load-bearing under
concurrent access.

### Task 3: XSD timestamp lexical forms and injectable clock [Medium]

*Depends-on: 1*

Implement `XmlDate`/`XmlDateTime` generation matching Java's
`XMLGregorianCalendar` lexical output (millisecond precision, local UTC offset)
on the `time` crate, plus the injectable-clock seam TaskStore's mutations use.
Golden-test the formats against timestamp strings from the Task 1 fixtures.

Medium: narrow surface, but it is the quietest divergence spot — a wrong
lexical form poisons every mutation fixture comparison downstream. Pulled
forward (dependency exception) because Task 4 hard-depends on it.

### Task 4: TaskStore mutations and depends-on extension [High]

*Depends-on: 2, 3*

Port `generatePlanTasks`, `addTask`/`nextTaskId`/`newPendingTask`,
`updateTaskStatus`, `addComment`, `addDeviation`, `setCommit`,
`projectUpdate`, `findTask` (with the exact `Task N not found` error),
`getTaskName`, `parseDependsOn`, and `get/setDependsOn` over the model's
raw-element list. Port `TaskStoreTest` alongside (its `parseTasksFromPlan`
usage retargets to `plan::parse_tasks`).

High risk: fresh-element construction must render byte-identically to JAXB
through `layout.rs` — this is the core of the slice and the part the plan/task
command leaves will sit on.

### Task 5: GitState [Medium]

*Depends-on: none*

Port `GitState` — `is_clean`, `current_branch`, `is_branch_pushed_and_not_ahead`,
`tag_exists_locally/on_remote`, `branch_exists`, `create_branch`,
`worktree_list` — on `run_lines` (spawn error → empty) and `run_exit_code`
(stderr merged, exact failure strings to stderr). Port `GitStateTest` (155
lines) in full against real temp git repos.

Medium: mechanical shelling with proven patterns from plan-106, but the
degradation and stderr contracts are asserted exactly.

### Task 6: GitTags [Medium]

*Depends-on: none*

Port `GitTags` — `get_plan_version`, `next_plan_version`, `highest_version_tag`
(git version-sort, first line), `tag_exists`, `create_tag` — preserving the
"no tag ≠ v1" distinction and the workDir rule (plan-70 comment). Port
`GitTagsIntegrationTest` (103 lines).

Medium: small and mechanical; the version-derivation logic is the only subtle
part and its test pins it.

### Task 7: PlanMarkdown slicing and TaskStore façade methods [Low]

*Depends-on: 4*

Port `PlanMarkdown.sliceTaskSection` into `ss-core::plan::markdown` with its
test, then the thin TaskStore façades: `slice_task_markdown` and
`format_plan_summary` (delegating to the already-ported summary formatter).

Low: pure string slicing plus one-line delegation over ported parts.

### Task 8: gw golden-replay harness [Medium]

*Depends-on: 4, 7*

The independent check: a Rust integration test that replays the Task 1 scripted
mutation sequence through the Rust `TaskStore` with a pinned clock and
byte-diffs every intermediate XML against the Java-written fixtures
(timestamps normalised via the captured Java values). Wire it into the standard
`cargo test` run alongside the existing round-trip tests.

Medium: ported tests inherit the porter's assumptions; this compares against
what the real Java binary wrote.

### Task 9: Migration notes write-back [Low]

*Depends-on: 8*

Update `docs/rust-migration/01-core.md` (§3 gw → ported status) and
`00-overview.md` with actual cost versus estimate, divergences found, decisions
that outlived the plan (injectable clock, parseTasksFromPlan dropped), and the
recommended next slice (plan/task command leaves + NewPlan/PlanService per
02-cli.md).
