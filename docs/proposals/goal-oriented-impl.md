# Goal-Oriented Implementation: Hybrid State & Coordination

**Status:** Proposal  
**Date:** May 2026  
**Subject:** Replacing fragmented recovery logic with a single reconciliation primitive, while keeping the ledger for what only the ledger can do.  
---

## 1. Abstract

> *The ledger doesn't tell us what is broken. It tells us if the break is still being worked on.*

Shipsmooth's current durable-progress model treats the append-only ledger as the primary source of truth. The development environment (Git branches, worktrees, conflict markers, test results) is treated as derivative. In practice this inverts reality: the environment is what actually exists, and the ledger is a narration of how it got there.

That inversion has produced a steady drip of resume bugs. Plan-36 alone shipped four corrective tasks (33, 36, 37, 38) for divergence between the ledger and the filesystem — `git reset --hard` wiping the ledger, integrate dying after fast-forwarding but before writing `PATCH_INTEGRATED`, stale `RESOLVER_REQUESTED` events with no live process to consume `RESOLVER_COMPLETE`. The SKILL's session-resume pre-flight has grown to a four-branch decision tree, and each branch encodes a specific past failure.

This proposal moves Shipsmooth to a **hybrid model**:

- **Git and the filesystem are the primary source of state.** What exists, exists. What was merged, was merged.
- **A thinned ledger remains the primary source of coordination.** Cross-process handshakes, audit, intent — the things the filesystem cannot represent — stay where they are.

The mechanism is a new diagnostic command, `shipsmooth-tasks reconcile`, which collapses the SKILL's recovery decision tree into a deterministic state-and-payload return value. The LLM stops walking branches; it asks the kernel where it is, and acts on the answer.

---

## 2. Non-Goals

This proposal is deliberately scoped. The following are **not** part of plan-37:

- **No background daemon.** `reconcile` runs when called — at session start, after a crash, or when the LLM has reason to doubt its state. There is no continuous controller.
- **No happy-path refactor.** The single-agent per-task loop (Phase 2 coding) remains event-driven. The de-risk-and-harden cycle, the per-task commits, the XML status flips — none of this changes.
- **No speculative caching.** A `verify`-result cache was considered and dropped. `reconcile` performs a fresh scan on every call. The cache addressed no measured bottleneck and would have added invalidation complexity disproportionate to its value. If profiling later shows `reconcile` is too slow, a cache can be added then.
- **Local-first.** Single-host execution is assumed throughout. Heartbeat-based lease detection uses local PID and filesystem checks. Distributed execution is out of scope.
- **No replacement of `INTEGRATION_PLAN`, `RESOLVER_REQUESTED/COMPLETE`, or `PATCH_INTEGRATED`.** These are load-bearing coordination events and remain.

---

## 3. The Truth Hierarchy

The single most important change is to make explicit which source of truth wins under disagreement. The current code is ambiguous; the proposal fixes the ambiguity.

### 3.1 Source ranking

1. **Ledger — primary source of recorded intent and coordination.** Status, claims, integration plans, resolver handshakes, deviations.
2. **Git + filesystem — primary source of realized state.** Branches, worktrees, commits, conflict markers, test outcomes.
3. **XML (`plan-{N}-tasks.xml`) — materialized projection.** Rewritten from the ledger on every status mutation. Optimized for human review and `git diff`. Not trusted by `reconcile`.

### 3.2 Reading discipline for `reconcile`

When `reconcile` runs, it reads the ledger and the environment, never the XML. Rationale: the XML is materialized *after* the ledger event is written, so a mid-mutation crash leaves the XML stale. The XML is for humans reading the file in their editor; the recovery tool reads upstream of the projection.

If `reconcile` detects drift between the ledger and the XML (any field), it opportunistically re-materializes the XML from the ledger before returning. This is a healing side-effect, not the tool's primary purpose.

### 3.3 Status writes

The CLI continues to write `STATUS_UPDATED` events to the ledger and rewrite the XML in the same call. This is already how `update-status` works today; the proposal codifies the invariant rather than introducing it.

---

## 4. The Thin Ledger

The ledger today carries 16 event types. Several of them duplicate information the filesystem already holds. The proposal removes those, keeping only events that the environment cannot reproduce.

### 4.1 Excised event types (derivable from environment)

| Event | Replaced by |
|---|---|
| `AGENT_START` | Process vitality check (heartbeat file + PID) |
| `CLEANUP` | Directory absence (`.agents/tasks/{id}` not present) |
| `WORKTREE_CREATED` | `git worktree list` |
| `PATCH_EMITTED` | `git rev-parse agent-work/{id}` |

The corresponding writers in `LedgerSubagentRunner`, `WorktreeService`, and the per-task commands are removed. Tests that assert against these events are deleted or rewritten to query the environment directly.

### 4.2 Retained event types (coordination, intent, audit)

| Event | Why it stays |
|---|---|
| `TASK_REGISTRATION` | Plan-level intent; defines what tasks exist |
| `STATUS_UPDATED` | Authoritative status; XML is its projection |
| `COMMENT_ADDED`, `DEVIATION_ADDED` | Audit trail; "why" content the git log cannot carry |
| `COMMIT_RECORDED` | Maps `task_id` → agent-work branch SHA; consumed by `integrate` |
| `PROJECT_UPDATE` | Plan-level state transitions (complete, abandoned, etc.) |
| `INTEGRATION_PLAN` | Session contract for `integrate`; scopes the resume window |
| `PATCH_INTEGRATED` | High-water mark for integration progress |
| `INTEGRATION_FAILURE`, `INTEGRATION_COMPLETE` | Terminal states of integration session |
| `RESOLVER_REQUESTED`, `RESOLVER_COMPLETE` | IPC handshake between `integrate` and the resolver dispatcher |

### 4.3 Why this is the right cut

The retained events share two properties: either no single process can observe them alone (the resolver handshake is a multi-party message bus) or they record reasoning that the filesystem cannot carry (deviations, comments). The excised events all map 1:1 to a single `git` or `ls` invocation. Storing them in the ledger created two places to look and two places to disagree.

---

## 5. The `reconcile` Diagnostic Command

`reconcile` is the central new primitive. It is the only piece of recovery logic the LLM sees; the rest is encoded in the kernel.

### 5.1 Invocation

```bash
runtime-0.2.0/bin/shipsmooth-tasks reconcile --plan {N}
```

Optional flags:

- `--force-break-stall` — for the human-confirmed-dead-process case. Causes `RESOLVER_PENDING_ALIVE` to be reported as `RESOLVER_STALLED` regardless of heartbeat freshness. Used by humans, not the LLM.
- `--json` — machine-readable output (the default; printed prose is also human-readable).

### 5.2 Output contract

A single JSON object with two top-level fields:

```json
{
  "state": "INTEGRATE_REPAIR",
  "payload": { "tasks": [3, 5], "integration_branch": "integration/plan-37" }
}
```

### 5.3 State enumeration

Six terminal states. The kernel returns exactly one per invocation.

#### `CLEAN_START`
Environment matches ledger intent. No worktrees pending, no integration in flight, no stalled resolvers. The agent proceeds with normal Phase 2 work. Payload is empty.

#### `RESUME_WORKTREE`
A task worktree (`.agents/tasks/{id}`) exists, the corresponding `STATUS_UPDATED` event shows the task is not yet `agent-coded`, and the heartbeat file is older than the stall threshold (5 min). The previous agent died mid-task. Payload: `{ task_id, worktree_path, last_commit_sha }`. The LLM resumes by dispatching a worker into the existing worktree.

#### `INTEGRATE_FF`
The integration branch (`integration/plan-{N}`) exists and is strictly ahead of the task branch HEAD. No `RESOLVER_REQUESTED` is outstanding. A prior session completed integration but crashed before fast-forwarding the task branch. Payload: `{ integration_sha, task_branch }`. The LLM runs `git merge --ff-only integration/plan-{N}` and pushes.

#### `INTEGRATE_REPAIR`
The integration branch contains commits whose `agent-work/{id}` branches have no corresponding `PATCH_INTEGRATED` event after the last `INTEGRATION_PLAN`. `integrate` died after the merge but before recording the event. Payload: `{ tasks: [id, …], integration_branch }`. The LLM calls `ledger-record-patch-integrated` for each listed task, then re-runs `integrate` to finish.

#### `RESOLVER_PENDING_ALIVE`
A `RESOLVER_REQUESTED` event exists with no matching `RESOLVER_COMPLETE`, and the `integrate` process heartbeat is within the stall threshold. `integrate` is alive and blocked waiting for the resolver. Payload: `{ task_id, resolver_payload_sha, integrate_pid }`. The LLM arms a Monitor on `ledger-watch` and lets integrate continue.

#### `RESOLVER_STALLED`
A `RESOLVER_REQUESTED` event exists with no matching `RESOLVER_COMPLETE`, and either the `integrate` heartbeat has timed out or `--force-break-stall` was passed. `integrate` is dead; `ledger-resolver-complete` would have no consumer. Payload: `{ task_id, resolver_payload_sha, worktree_path, agent_work_sha }`. The LLM follows the five-step manual recovery: dispatch the resolver, commit in the worktree, call `ledger-record-patch-integrated`, re-run `integrate`.

### 5.4 Snapshot semantics

`reconcile` takes a point-in-time read. Between the moment it reads the ledger and the moment it reads `git worktree list`, the environment can change. The tool does not lock. If a mutation lands mid-scan, the next `reconcile` call sees the updated state. This is acceptable because `reconcile` is diagnostic, not a controller — the LLM acts on its output, then moves on.

### 5.5 Failure modes

If `reconcile` itself cannot run (corrupt ledger, missing XML, unreadable worktree), it exits non-zero with a human-readable error and no JSON payload. The LLM should surface the error to the human; no recovery is attempted.

---

## 6. Heartbeat-Based Lease Detection

The "zombie process" problem — a `TASK_CLAIMED` or `RESOLVER_REQUESTED` recorded by a process that has since died — needs liveness signal that the ledger alone cannot provide.

### 6.1 Mechanism

Long-running processes (`integrate`, `worker-init`-spawned worker wrappers) write a heartbeat file at `.agents/heartbeat/{id}` every 60 seconds. The file contains `{ pid, host, ts }`. On clean exit, the process deletes its heartbeat file.

`reconcile` reads the heartbeat file and compares `ts` to wall-clock time. If the gap exceeds **5 minutes**, the process is declared stalled.

### 6.2 Threshold rationale

Five minutes aligns with the Anthropic prompt cache TTL. An agent that has not produced a tool call in five minutes has lost its cache anyway and is almost certainly hung. A tighter threshold (e.g., 60 seconds) would false-positive on long thinking turns and long-running `verify` invocations.

### 6.3 Writer scope

In this plan, only `integrate` and `worker-init`-spawned worker wrappers carry heartbeats. The single-agent sequential case (Lead Agent doing Phase 2 work directly in the main repo) does not have a wrapper process and does not write a heartbeat — its claim is implicit in the conversation's continuation and there is no other agent that could pick up the work. Adding heartbeat support to the Lead Agent path is out of scope for plan-37.

### 6.4 Storage location and gitignore

Heartbeat files live under `.agents/heartbeat/`. This directory is added to `.gitignore` alongside `.agents/objects` (already excluded) so that `git reset --hard` cannot delete in-flight liveness signals.

### 6.5 Manual override

`reconcile --force-break-stall` exists for the case where a human has independently confirmed a process is dead (e.g., they killed it) and wants to skip the 5-minute wait. The flag downgrades any `RESOLVER_PENDING_ALIVE` to `RESOLVER_STALLED` in the output and is not callable by the LLM (the SKILL does not document it).

---

## 7. Impact on the SKILL

The current SKILL session-resume pre-flight has a four-branch decision tree with a five-step manual recovery embedded in one branch. After plan-37 lands, the section collapses to:

```markdown
**Session-resume pre-flight [Local]** — If you are picking up a plan that
was started in a previous session, run reconcile and follow its output:

  runtime-0.2.0/bin/shipsmooth-tasks reconcile --plan {N}

The output is a JSON object with `state` and `payload`. Act according to
the state:

  CLEAN_START             — proceed with normal Phase 2 work.
  RESUME_WORKTREE         — dispatch a worker into payload.worktree_path.
  INTEGRATE_FF            — git merge --ff-only payload.integration_branch && git push.
  INTEGRATE_REPAIR        — for each task in payload.tasks, call
                            ledger-record-patch-integrated; then re-run integrate.
  RESOLVER_PENDING_ALIVE  — arm Monitor on ledger-watch and let integrate continue.
  RESOLVER_STALLED        — follow the manual resolver recovery path.

Do not attempt to diagnose recovery state by hand. Trust reconcile.
```

The 5-step manual resolver recovery stays in the SKILL — it is the only branch with substantive LLM work — but it is now reachable only via `RESOLVER_STALLED`, never by hand-deduction.

---

## 8. Implementation Plan

Four tasks. Risk-sorted (High first, with dependencies preserved).

| # | Risk | Name | Description |
|---|---|---|---|
| 1 | **High** | `reconcile` command | Build the diagnostic in the Java kernel: six states, payload schema, fresh-scan reads, XML re-materialization on detected drift. Includes the JSON output contract and `--force-break-stall` flag. Heaviest task by far. |
| 2 | **High** | SKILL migration | Replace the four-branch recovery tree in `SKILL.jte.md` with the `reconcile`-driven dispatch table. High because it is the contract with the LLM; getting the state-to-action mapping wrong silently breaks recovery. Depends on (1). |
| 3 | **Medium** | Heartbeat wrapper | Wrapper process for `integrate` and `worker-init`-spawned workers. Writes `.agents/heartbeat/{id}` every 60s, deletes on clean exit. Adds `.agents/heartbeat/` to `.gitignore`. |
| 4 | **Medium** | Excise derivable events + lock in XML projection | Remove `AGENT_START`, `CLEANUP`, `WORKTREE_CREATED`, `PATCH_EMITTED` from `EventType` and all writers/readers. Codify the contract that every `STATUS_UPDATED` write also re-materializes the XML. Update tests. Touches several commands; not Low. |

### Dependency graph

- Task 2 depends on Task 1 (the SKILL needs the command to exist).
- Tasks 3 and 4 are independent of each other and of Tasks 1–2; they can run in any order.

### Estimated scope

Roughly two days of focused work. Task 1 is the bulk; Task 2 is small but contract-critical; Tasks 3 and 4 are mechanical refactors with clear boundaries.

### Out of scope, for the record

- State cache for `reconcile` output. Add after measurement.
- Heartbeat support for the single-agent Lead Agent path. Add when sequential stalls become a measured problem.
- Distributed / multi-host execution.

---

## 9. Risks and Mitigations

**Drift between `reconcile` output and the SKILL's dispatch table.** If the kernel adds a state and the SKILL doesn't, the LLM sees a state it doesn't know how to handle. Mitigation: the SKILL's state list is generated from the same source as the kernel's enum (JTE partial backed by a generated table), or the kernel rejects unknown states from its own enum at startup. The simpler mitigation — keep both in sync by hand and add a startup self-check — is fine for plan-37; the generated approach can come later if drift recurs.

**Heartbeat staleness on legitimately long operations.** A `verify` command that takes >5 minutes will cause `reconcile` to report the integrate process as stalled while it is in fact running. Mitigation: the heartbeat wrapper writes the heartbeat in its own thread on a 60s timer, independent of what the foreground process is doing. The wrapper exits cleanly only when its child exits, so heartbeats continue throughout long subcommands.

**XML re-materialization races.** If `reconcile` detects drift and rewrites the XML while another `shipsmooth-tasks` mutation is also rewriting it, the file can be torn. Mitigation: the CLI already uses atomic XML writes (per the plan-36 project update). `reconcile`'s rewrite goes through the same `XmlService` path; no new race surface is introduced.

**`reconcile` returns `CLEAN_START` when the environment is actually corrupt.** False-clean is the worst failure mode — the LLM proceeds and steps on existing state. Mitigation: `reconcile` validates a small set of invariants before returning `CLEAN_START` (no orphan agent-work branches without `COMMIT_RECORDED`, no integration worktree without `INTEGRATION_PLAN`, no heartbeat files without a matching task). Any invariant violation produces a diagnostic state instead, even if no existing state code fits — fall through to a new `INVARIANT_VIOLATION` state with a descriptive payload.

That last point promotes the state count to seven. Adding it now (in spec) rather than after the first false-clean incident is cheap.

---

## 10. Conclusion

The current architecture treats the ledger as the authority and the environment as derivative. The accumulating recovery patches reveal that to be backward: the environment is what exists, and the ledger is most useful when it carries only the things the environment cannot represent.

This proposal makes that inversion explicit. It removes the events that duplicate filesystem state, retains the events that coordinate cross-process work, and introduces a single recovery primitive that the LLM can call instead of walking a decision tree. The LLM remains stateful within a session — its context window and conversation history are real and useful — but when that state proves wrong, `reconcile` is the cheap, deterministic fallback that puts it back on the rails.

The win is measured in the size of the SKILL's recovery section and the number of foot-guns it documents. Plan-36 added four tasks for recovery bugs. Plan-37 should be the last plan that has to.