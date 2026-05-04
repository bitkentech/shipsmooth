# Plan 34 — Ledger-Backed Task Tracking (Phase 1)

## Context

Shipsmooth currently tracks plan execution in `.agents/plans/plan-{N}-tasks.xml`, mutated by `shipsmooth-tasks` subcommands (`update-status`, `add-comment`, `add-deviation`, `set-commit`, `project-update`). Each command rewrites the whole XML file via JAXB. This is fine for one sequential agent but races under any concurrent writers.

The longer-term goal (separate Phase 2 plan) is parallel execution of Low-risk tasks: Claude Code spawns multiple coding subagents, each in an isolated git worktree, with results integrated by a 3-way merge service. The coordination primitive that makes this safe is the agent-simulator's append-only `ledger.jsonl` + content-addressed object store at `.agents/objects/` (verified in `/opt/workspace/agent-simulator/src/main/java/com/pramodb/agentsim/service/{LedgerService,GitWorktreeService,IntegrationService}.java`).

This plan covers **only Phase 1**: introduce the ledger as a parallel-write companion to the existing XML, without changing any execution semantics. No worktrees, no parallel dispatch, no integration service yet. After this lands, every task event is durably and concurrently recorded; the follow-up plan can build parallelism on top with no migration.

Permanent backlog feature: *Parallel coding-subagent execution* (informal — recent plans in this repo leave `<backlog-issue>` empty; matching that convention).

Mode: `[Local]`.

---

## Design summary

- **Lift** `LedgerService` and the `writeObject`/`readObject` half of `GitWorktreeService` into `plugin-tasks-java`. Strip Spring (`@Service`, `@Slf4j`, `@Qualifier`) — the substrate is plain Java + Jackson + `java.nio`, both already on the CLI's classpath.
- **Drop** Phase-2-only methods: `addWorktree*`, `applyPatch3way`, `commitAll`, `diff`, `removeWorktree`, `resetToBaseline`, `prune`, the `gitGate` semaphore, and the entire `IntegrationService` polling loop. Phase 2 will lift these.
- **Reduce** `EventType` for now to: `TASK_REGISTRATION`, `STATUS_UPDATED`, `COMMENT_ADDED`, `DEVIATION_ADDED`, `COMMIT_RECORDED`, `PROJECT_UPDATE`. Phase 2 adds `AGENT_START`, `PATCH_EMITTED`, `PATCH_INTEGRATED`, `INTEGRATION_FAILURE`.
- **Wire** each existing mutating command (`Init`, `UpdateStatus`, `AddComment`, `AddDeviation`, `SetCommit`, `ProjectUpdate`) to record a ledger event after the XML write succeeds. XML stays the human-readable source of truth; ledger is the machine-readable execution trace.
- **Expose** `shipsmooth-tasks ledger {list,verify,read}` for inspection.
- **Bootstrap** `.agents/objects/` and `.agents/ledger.jsonl` on `init`, plus a `.gitignore` policy: track `.agents/ledger.jsonl` and `.agents/objects/`, ignore `.agents/tasks/*` and `.agents/integration/*` (latter two anticipate Phase 2 but cost nothing now).
- **Task ID namespace**: ledger `taskId` = the XML task ID as a string (`"3"`). Identical to what Phase 2 will need, so no migration.

Files modified (existing references):
- `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/TasksCli.java` — register new subcommand
- `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/commands/{Init,UpdateStatus,AddComment,AddDeviation,SetCommit,ProjectUpdate}Command.java` — add ledger record after XML write
- `plugin-skill/src/main/jte-src/skills/SKILL.jte.md` — one-line note in Execute phase

Files added:
- `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/ledger/{LedgerService,ObjectStore,Event,EventType,GitignoreManager}.java`
- `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/commands/LedgerCommand.java` (with `list`/`verify`/`read` picocli subcommands)
- `plugin-tasks-java/src/test/java/.../ledger/LedgerServiceTest.java`

Out of scope, deferred to a subsequent plan:
- Worktrees, `applyPatch3way`, `commitAll`, integration service, SubagentStop hook, parallel-dispatch skill section.
- Cross-process concurrency testing — single-agent use only for now; rough edges around multi-process append safety are acceptable until Phase 2.
- Backfilling old plans (29–33) into the ledger. Ledger is forward-only from plan-34.

---

## Tasks

### Task 1: Lift ledger substrate into plugin-tasks-java [Medium]

Copy `LedgerService.java` and the `writeObject`/`readObject`/`repoRoot` slice of `GitWorktreeService.java` from `/opt/workspace/agent-simulator/src/main/java/com/pramodb/agentsim/service/` into `plugin-tasks-java/.../ledger/`. Strip Spring annotations; replace `@Slf4j` with stderr or `java.util.logging`. Lift `Event` and reduce `EventType` to the six Phase 1 event kinds. Verify byte-for-byte SHA-1 equivalence with `git hash-object` in a unit test.

### Task 2: Wire ledger writes into mutating commands [Medium]

For each of `UpdateStatusCommand`, `AddCommentCommand`, `AddDeviationCommand`, `SetCommitCommand`, `ProjectUpdateCommand`: after the XML write succeeds, record one ledger event with the appropriate type and payload. If ledger record fails after XML succeeds, surface the error but leave the XML mutation in place (Phase 2 makes ledger authoritative; Phase 1 is additive).

### Task 3: Bootstrap .agents layout and .gitignore on init [Low]

Extend `InitCommand` to create `.agents/objects/`, touch `.agents/ledger.jsonl`, and append the four `.gitignore` entries (`.agents/tasks/*`, `.agents/integration/*`, `!.agents/ledger.jsonl`, `!.agents/objects/`) idempotently. Mirror the logic in `GitWorktreeService.ensureGitignore()`. Emit one `TASK_REGISTRATION` event per task generated from the plan markdown.

### Task 4: Add `shipsmooth-tasks ledger` subcommand [Low]

New picocli command `LedgerCommand` with three subcommands:
- `list [--task <id>] [--type <EVENT_TYPE>]` — prints `[idx] sha8 TYPE | taskId | timestamp | summary`
- `verify` — runs full timeline reconstruction, exits non-zero if any object missing/unparseable
- `read <sha>` — prints the JSON event blob

Register in `TasksCli.java`.

### Task 5: Update existing command/integration tests [Low]

Extend `CommandsTest` and `TasksCliIntegrationTest` to assert that after each mutating command, exactly one new ledger entry exists with the expected type and taskId. Add an `init` test that asserts the gitignore entries were appended and the layout exists.

### Task 6: Document ledger in SKILL [Low]

One-line note in `plugin-skill/src/main/jte-src/skills/SKILL.jte.md` Execute phase: "All `shipsmooth-tasks` mutations are also recorded to `.agents/ledger.jsonl` for crash recovery and (in future plans) parallel execution. The XML remains the human-readable source of truth." No workflow change for the human.

---

## Verification (end-to-end, after all tasks done)

1. `mvn -pl plugin-tasks-java test` passes.
2. On this repo:
   - From the `ledger-task-tracking` branch, run `shipsmooth-tasks init --plan 34 --tasks-from .agents/plans/plan-34.md` (well, a future plan — plan-34's own XML is generated by the *current* CLI, not the new code).
   - Run a few `update-status`, `add-comment`, `set-commit` commands against a draft plan-35 or scratch plan.
   - `shipsmooth-tasks ledger list` shows one event per command in order.
   - `shipsmooth-tasks ledger verify` exits 0.
   - `git status` shows `.agents/ledger.jsonl` and `.agents/objects/**` tracked, `.agents/tasks/` and `.agents/integration/` ignored.
3. Fault injection: chmod `ledger.jsonl` to read-only mid-test; confirm the command surfaces the error but leaves the XML mutation in place.

---

## Phase 2 preview (not in scope)

So Phase 1 doesn't paint Phase 2 into a corner: Phase 2 lifts the rest of `GitWorktreeService` (worktree create/remove, `applyPatch3way`, `commitAll`) and a thinned `IntegrationService` (no polling loop — replaced by an `integrate --once` tool driven by a SubagentStop hook). Phase 2 adds the four deferred event types, gates parallelism to Low-risk tasks in the skill, chooses per-plan vs global integration worktree, and adds cross-process concurrency tests. The `taskId` namespace and `.gitignore` policy chosen here are deliberately the same as Phase 2 needs.
