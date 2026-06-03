@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Agent Coding Workflow (TLA-checked ledger)

## When to apply this skill
Apply this skill whenever you are:
- Starting work on a new feature or task
- Asked to write, revise, or execute a plan
- Picking up existing work from Linear
- Closing out, abandoning, or handing off a plan

This skill tracks task progress through a **content-addressed ledger** whose state
transitions are formally specified by the `ShipSmooth_Milestones` TLA⁺ model
(reproduced below). Only the four milestone events in that model are ever written:
`TASK_CREATED`, `DE_RISK_FINISHED`, `HARDEN_FINISHED`, `TASK_FINISHED`.

---

## Core Invariants — Never Violate These

1. **Features vs Plans are strictly separate.** Feature issues live in the permanent backlog forever. Plan issues live in transient `[agent]` projects and are archived after completion. Never create feature issues inside an `[agent]` project.
2. **A committed, pushed, human-reviewed plan is the contract.** You execute against it. You do not autonomously modify it.
3. **Every plan must reference at least one permanent backlog feature issue.** `[Linear]` Create an `[agent]` project linking to it. `[Local]` Record it in the `<backlog-issue>` metadata element of the tasks XML file. If no backlog issue exists, stop and create one before proceeding.
4. **Task tracking is never the source of truth for plan content.** Git is. Linear (or the local tasks file) tracks task state only.
5. **Tags are permanent.** Never delete a plan version tag from remote, even on abandonment or squash merge.
6. **Tests precede implementation.** Write integration test(s) before any task code (Phase 2 preamble), then the unit test for each task before its implementation. Never implement without a failing test already committed. (Apply as far as possible — migrations and config may not be TDD-able.)
7. **The ledger only ever records the four model milestones.** Every ledger event is one of `TASK_CREATED`, `DE_RISK_FINISHED`, `HARDEN_FINISHED`, `TASK_FINISHED`, written in that order per task. No other event types exist in this workflow.

---

## Task Tracking Mode

This workflow supports two task tracking modes. Choose one at the start of each plan:

- **`[Linear]`** — Uses Linear issues and projects. Requires a Linear account and the Linear MCP server configured in Claude Code.
- **`[Local]`** — Uses a local XML file at `.agents/plans/plan-{N}-tasks.xml` plus the append-only ledger at `.agents/ledger.jsonl`. No external services and no Java runtime are required — the ledger is written directly with shell commands (see "Writing Ledger Events" below).

Throughout this skill, instructions marked `[Linear]` apply only in Linear mode; instructions marked `[Local]` apply only in Local mode. Unmarked instructions apply to both.

---

## Control Strategy: The Risk-Quality Loop

To maximize productivity while minimizing "hallucination drift," treat
risk and quality as two pressures that peak at different times — and never
chase both at once.

- **Spiral risk** — the chance that the architecture or core logic is
  simply wrong. It is highest at the *start* of a task, when the approach
  is unproven, and collapses once the logic is validated.
- **Implementation quality** — readability, project-pattern conformance,
  and test coverage. It matters only *after* the approach is proven; polishing
  code that may be thrown away is wasted effort.

**Strategy:** De-risk aggressively first — prove the logic works and ignore
quality rules. Once the approach is validated and approved, switch modes and
harden the code to the quality bar. The per-task **De-risk & Harden Cycle**
below operationalizes this; this section only explains *why* the two phases
are kept separate.

---

## Repository Structure

```
.agents/
  plans/
    plan-07.md            # plan files live here, versioned in git
    plan-07-tasks.xml     # [Local] task state (sibling to plan file)
  ledger.jsonl            # [Local] append-only list of event SHAs, one per line
  objects/                # [Local] content-addressed event blobs (git-style fan-out)
    ab/
      cdef0123...         # JSON event blob, named by SHA-1 minus first 2 chars
```

Plans are markdown files. They contain: narrative, design decisions, architecture notes, open questions, and references. Code never goes here.

---

## Git Tagging Convention

Every time a plan file is committed and pushed, immediately create and push a version tag:

```bash
# After committing a plan file change:
git tag plan-07-v1
git push origin plan-07-v1

# Subsequent revisions:
git tag plan-07-v2
git push origin plan-07-v2

# On clean completion:
git tag plan-07-complete
git push origin plan-07-complete

# On abandonment (tag the deletion commit too):
git tag plan-07-abandoned
git push origin plan-07-abandoned
```

Tag naming: `plan-{N}-v{version}` for iterations, `plan-{N}-complete` for clean closeout, `plan-{N}-abandoned` for abandonment.

### Automate with lefthook

Commit a hook so tagging fires automatically on every push, regardless of whether a human or agent made the commit:

```yaml
# lefthook.yml
pre-push:
  commands:
    auto-tag-plans:
      run: |
        # Detect if any .agents/plans/ file changed in the push
        if git diff --name-only HEAD~1 HEAD | grep -q '^\.agents/plans/'; then
          PLAN=$(git diff --name-only HEAD~1 HEAD | grep '^\.agents/plans/' | head -1)
          PLAN_ID=$(echo "$PLAN" | grep -oP 'plan-\d+')
          # Find next version number
          LATEST=$(git tag -l "${"${"}PLAN_ID}-v*" | sort -V | tail -1)
          if [ -z "$LATEST" ]; then
            NEXT="${"${"}PLAN_ID}-v1"
          else
            N=$(echo "$LATEST" | grep -oP '\d+$')
            NEXT="${"${"}PLAN_ID}-v$((N+1))"
          fi
          git tag "$NEXT"
          git push origin "$NEXT"
          echo "Auto-tagged: $NEXT"
        fi
```

Install lefthook if not present: `npm install -g lefthook && lefthook install`

---

## Linear Structure

`[Linear]` only. Skip this section in Local mode.

### Permanent Backlog Project
- Named e.g. `AppName — Backlog & Roadmap`
- Contains feature issues only
- Human-created and human-prioritised
- Never deleted, survives all plan lifecycles

### Transient Agent Projects
- Named: `[agent] {N} · {short-description}` e.g. `[agent] 07 · home-accounts-settings-bottom-tabs`
- Created per plan, archived after completion
- Project description must contain:
  - Link to the permanent backlog feature issue(s) it delivers
  - Permalink to the plan file using the tag-based commit hash URL (see below)
  - Brief plan narrative / design rationale

### Tag-based GitHub permalink format
```
https://github.com/{org}/{repo}/blob/{tag-commit-hash}/.agents/plans/plan-07.md
```

Resolve the commit hash for a tag:
```bash
git rev-list -n 1 plan-07-v1
```

Use this hash (not the tag name) in Linear links — it is immutable and survives branch deletion, rebases, and squash merges.

---

## Phase 1 — Plan, Calibrate, & Commit

**You do not write or run any implementation code during this phase.**

1. **Draft Plan:** Write or update the plan file at `.agents/plans/plan-{N}.md`.
2. **Risk Analysis:**
   - For every task in the plan, suggest a **Default Risk Level** (Low, Medium, or High) with a one-sentence justification.
3. **Collaborative Calibration:**
   - **Stop.** Ask the human: *"I've estimated these risk levels. Do you want to override any of them?"*
   - The human's choice becomes the **Actual Risk ($R$)**.
4. **Risk-Sorted Task Ordering:**
   - Re-order tasks in the plan file in **descending order of risk** ($High \to Med \to Low$).
   - *Exception:* If a Low-risk task is a hard technical dependency for a High-risk task, the dependency must come first.
5. **Commit & Tag:**
   ```bash
   git add .agents/plans/plan-07.md
   git commit -m "plan(07): risk-calibrated plan for [short-description]"
   git push origin t/{issue-id}-{short-description}
   # Lefthook auto-tags plan-07-v1 and pushes it
   ```
6. **Verify Preconditions:**
   ```bash
   git status                          # must be clean
   git log origin/t/{issue-id}-{short-description}..HEAD  # must be empty
   git tag -l "plan-07-v1"             # must exist
   git ls-remote origin "plan-07-v1"   # must be on remote
   ```
7. **Create Task Tracking Infrastructure:**
   - `[Linear]` Create the `[agent]` Linear project. Create Linear issues from the **risk-sorted** plan tasks. Each issue description must include the **Risk Level** ($L/M/H$) and the tag-based GitHub URL of the specific plan version that generated it.
   - `[Local]` Create `.agents/plans/plan-{N}-tasks.xml` listing the risk-sorted tasks. Each `<task>` element carries a stable `id` (a positive integer), a `<name>`, a `<risk>` (Low/Medium/High), and a `<status>` of `pending`. Commit the XML file immediately after creation. Use task IDs of the form `1`, `2`, `3` — these IDs are what you pass when writing ledger events.
   - Organise tasks as **thin vertical slices** in both modes.
8. **Final Review & Go-ahead:**
   - `[Linear]` **Stop.** Post to the Linear project that the risk-sorted plan is ready for review.
   - `[Local]` **Stop.** Tell the human the XML task file has been committed and the plan is ready for review.
   - **Wait for explicit human go-ahead before proceeding to Phase 2.**

---

## The Formal Model — `ShipSmooth_Milestones`

`[Local]` The ledger's valid states and transitions are specified by this TLA⁺ model. Treat it as the authoritative definition of what sequences of ledger events are legal. When resuming a plan, reconstruct each task's milestone sequence from the ledger and check it against this model before doing anything else.

@template.skills.experimental.start-tla.tla-model(model = model)

**Reading the model in terms of this skill:**

- `ledger[t]` is the ordered list of milestone events recorded for task `t`. In practice you reconstruct it by reading `.agents/ledger.jsonl` top-to-bottom and collecting the `event_type` of every event whose `task_id` is `t`.
- `RegisterTask(t)` — appends `TASK_CREATED`. Only legal when the task has no events yet.
- `FinishDeRisk(t)` — appends `DE_RISK_FINISHED`. Legal once, after `TASK_CREATED`.
- `FinishHarden(t)` — appends `HARDEN_FINISHED`. Legal once, after `DE_RISK_FINISHED`.
- `FinalizeTask(t)` — appends `TASK_FINISHED`. The terminal milestone for a task.
- `heartbeat` / `AcquireTask` / `Crash` / `Reconcile` model the OS-process lock and crash recovery. There is no separate lock file in this skill — a "crash" is simply your session ending mid-task. On resume, `Reconcile` corresponds to the session-resume pre-flight below: confirm each task's ledger history is one of the valid shapes (empty, registered-but-not-finished, or finished) before continuing.
- `LedgerIntegrity` invariant: every non-empty task history **must** begin with `TASK_CREATED`. Never write a `DE_RISK_FINISHED`, `HARDEN_FINISHED`, or `TASK_FINISHED` event for a task that has no `TASK_CREATED` event.
---

## Writing Ledger Events `[Local]`

There is **no Java runtime and no CLI** in this workflow. You write ledger events yourself with plain shell commands. An event is recorded in two steps, exactly mirroring a git blob:

### 1. The event JSON structure

Each event is a single JSON object. Field order does not matter, but use exactly these keys:

```json
{
  "event_type": "TASK_CREATED",
  "timestamp": "2026-05-14T09:30:00Z",
  "task_id": "1",
  "payload": "Risk-sorted task registered from plan-07-v1",
  "metadata": {
    "plan": "7",
    "risk": "High"
  }
}
```

- `event_type` — one of `TASK_CREATED`, `DE_RISK_FINISHED`, `HARDEN_FINISHED`, `TASK_FINISHED`. Nothing else.
- `timestamp` — ISO-8601 UTC, e.g. `$(date -u +%Y-%m-%dT%H:%M:%SZ)`.
- `task_id` — the task's stable integer id from `plan-{N}-tasks.xml`, as a string.
- `payload` — a short human-readable description of the milestone (free text).
- `metadata` — a flat string→string map. Always include `plan`; include `risk` on `TASK_CREATED`. Omit the key entirely if there is nothing to put in it.

Keep the JSON compact and write it with **no trailing newline** — the SHA is computed over the exact bytes.

### 2. Compute the SHA and store the blob

The blob hash is a **git-style SHA-1**: SHA-1 over the bytes `blob <byte-length>\0` followed by the JSON bytes. Store the JSON under `.agents/objects/<first-2-hex>/<remaining-38-hex>`, then append the full 40-char SHA as a new line to `.agents/ledger.jsonl`.

The simplest reliable way is `git hash-object -w`, which computes exactly this hash **and** writes the blob — but it writes into `.git/objects`, not `.agents/objects`. So compute the hash with `git hash-object` and place the file yourself:

```bash
record_event() {
  # $1 = compact JSON string for the event
  local json="$1"
  mkdir -p .agents/objects
  # git-style blob SHA-1 over "blob <len>\0<json>"
  local sha
  sha=$(printf '%s' "$json" | git hash-object --stdin -t blob)
  local dir=".agents/objects/${"${"}sha:0:2}"
  local file="${"${"}dir}/${"${"}sha:2}"
  mkdir -p "$dir"
  # content-addressed: identical bytes -> identical sha, write-once
  [ -f "$file" ] || printf '%s' "$json" > "$file"
  printf '%s\n' "$sha" >> .agents/ledger.jsonl
  echo "$sha"
}
```

Example — registering task 1:

```bash
ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
json=$(printf '{"event_type":"TASK_CREATED","timestamp":"%s","task_id":"1","payload":"Registered from plan-07-v1","metadata":{"plan":"7","risk":"High"}}' "$ts")
record_event "$json"
```

`git hash-object --stdin -t blob` produces the identical SHA-1 to the formula above, so the ledger is verifiable: re-hash any object file's contents and it must equal its path-derived name.

### 3. Reading the ledger back

To reconstruct a task's milestone history:

```bash
# list every event for task 1, in order
while read -r sha; do
  cat ".agents/objects/${"${"}sha:0:2}/${"${"}sha:2}"
  echo
done < .agents/ledger.jsonl | jq -c 'select(.task_id == "1") | .event_type'
```

To verify ledger integrity, re-hash each referenced object and confirm it matches its filename:

```bash
while read -r sha; do
  f=".agents/objects/${"${"}sha:0:2}/${"${"}sha:2}"
  actual=$(git hash-object "$f" -t blob)
  [ "$actual" = "$sha" ] || echo "CORRUPT: $sha"
done < .agents/ledger.jsonl
```

Commit `.agents/ledger.jsonl` and `.agents/objects/` alongside the task commit they describe — the ledger is part of the audit trail and is versioned in git.

---

## Phase 2 — Execute

**Session-resume pre-flight `[Local]`** — If you are picking up a plan that was started in a previous session, reconstruct every task's milestone sequence from the ledger (see "Reading the ledger back" above) and check each against the `ShipSmooth_Milestones` model. A valid task history is exactly one of:

- empty (`<< >>`) — task not yet started,
- starts with `TASK_CREATED` and has not reached `TASK_FINISHED` — task registered, in progress,
- ends with `TASK_FINISHED` — task complete.

Any other shape (e.g. `HARDEN_FINISHED` with no preceding `DE_RISK_FINISHED`, or any event before `TASK_CREATED`) violates the model — stop and surface it to the human. Only proceed once you know which tasks are done and which are next.

---

**Step 0: Create a branch**

Create and push a branch named after the primary Linear issue for this plan:
```bash
git checkout -b t/{issue-id}-{short-description}
# e.g. git checkout -b t/pb-149-branch-creation-step
git push -u origin t/{issue-id}-{short-description}
```
All task commits go on this branch. The `t/` prefix stands for "task" (covers features, bugs, chores, etc.). Usernames are intentionally omitted — the task identity is what matters long-term.

**Before writing any code**, confirm the test coverage threshold with the human (default: 95%). Record the agreed value before proceeding.

`[Local]` Immediately after the XML task file is committed and before the first task starts, write one `TASK_CREATED` ledger event per task (in risk-sorted order), following "Writing Ledger Events" above. This is the `RegisterTask(t)` transition for every task. The XML remains the human-readable source of truth; the ledger is the machine-readable execution trace.

### Preamble: integration tests (once, before any task)

1. Write 1–2 integration tests that exercise the feature end-to-end. No more than two.
2. Commit and push them with no implementation — they must fail (red). `[Linear]` Reference the Linear project in the commit message.
3. Confirm red state:
   ```bash
   # run your project's test command, e.g.:
   npm test          # or: pytest, go test ./..., etc.
   ```
   If a test passes at this point, it is testing the wrong thing. Fix or discard it before continuing.

### Per-task loop (The De-risk & Harden Cycle)

For every task in the risk-sorted sequence, apply the appropriate sub-phases.

#### High and Medium risk tasks — De-risk & Harden Cycle

##### Step A: De-risking (Spiral Phase)
- **Goal:** Validate logic and architectural direction. Ignore "Implementation Quality" rules.
- Write at least one failing test that targets the core logic (preserving Core Invariant #6).
- Implement just enough to prove the approach works. Focus on the core complexity.
- Commit as `draft(N): de-risk [task name]`.
- `[Local]` Write a `DE_RISK_FINISHED` ledger event for this task (`FinishDeRisk(t)`). Set `payload` to a one-line summary of what was de-risked; `metadata.plan` to the plan number. Update the task's `<status>` to `de-risked` in the XML.
- `[Linear]` Post a comment on the Linear issue notifying the human the draft is ready.
- **Wait for explicit approval of the approach.**

##### Step B: Hardening (Quality Phase)
- **Goal:** Achieve technical excellence, human readability, and coverage threshold.
- Refactor the de-risked code for readability, performance, and project patterns.
- Write full unit tests and ensure coverage meets the agreed threshold:
  ```bash
  # example — adjust to your toolchain:
  npm test -- --coverage --coverageThreshold='{"global":{"lines":95}}'
  ```
- Commit the completed task (tests + implementation):
  ```bash
  git commit -m "task(N): <short description>"
  git push origin t/{issue-id}-{short-description}
  ```
  This creates a stable rollback point. A human reviewing the PR can check out this commit to inspect each task in isolation.
- `[Local]` Write a `HARDEN_FINISHED` ledger event for this task (`FinishHarden(t)`). Set `payload` to the hardened commit's short description; add `metadata.commit` with the commit SHA from `git rev-parse HEAD`. Update the task's `<status>` to `agent-coded` in the XML.
- `[Linear]` Mark the Linear issue **Agent Coded**.

##### Step C: Finalize the task
- Once hardening is committed and (in `[Linear]` mode) the issue is marked Agent Coded, the task is complete.
- `[Local]` Write a `TASK_FINISHED` ledger event for this task (`FinalizeTask(t)`). Set `payload` to a one-line completion note; `metadata.plan` to the plan number. Update the task's `<status>` to `done` in the XML.

#### Low risk tasks — Single-pass

1. Write the unit test(s) for this task. Commit them failing (red).
2. Implement the task. Run tests until green.
3. Check coverage meets the agreed threshold:
   ```bash
   # example — adjust to your toolchain:
   npm test -- --coverage --coverageThreshold='{"global":{"lines":95}}'
   ```
   Do not proceed until coverage passes.
4. Commit the completed task (tests + implementation):
   ```bash
   git commit -m "task(N): <short description>"
   git push origin t/{issue-id}-{short-description}
   ```
   - `[Linear]` Mark the Linear issue **Agent Coded**. No draft review needed.
5. `[Local]` A low-risk task still passes through the same milestone sequence — the model requires `DE_RISK_FINISHED` and `HARDEN_FINISHED` before `TASK_FINISHED`. Since there is no separate de-risk pass, write all three in order, immediately after the task commit:
   - `DE_RISK_FINISHED` — `payload`: "Low-risk task, single-pass; no separate de-risk."
   - `HARDEN_FINISHED` — `payload`: the commit description; `metadata.commit`: the commit SHA.
   - `TASK_FINISHED` — `payload`: one-line completion note.
   Then update the task's `<status>` to `done` in the XML.

---

- **Minor deviation** (task split, reorder, clarification):
  - `[Linear]` Update the Linear issue(s), add a deviation comment explaining why, continue.
  - `[Local]` Note the deviation in the plan file's open-questions section (or task XML `<notes>`), and continue. Do not invent new ledger event types — the ledger only carries the four milestones.
- **Major deviation** (fundamental plan problem, architecture issue, blocked): Stop immediately.
  - `[Linear]` Post a Linear project update. Set project health to **"At Risk"**.
  - `[Local]` Tell the human the plan is blocked and why. Do not write a ledger event for this — wait for the human to revise the plan file, commit, push, and give a new go-ahead.

Never autonomously modify the `.agents/plans/` file during execution. If a plan change is needed, surface it and wait.

---

## Plan Closeout

### Clean Completion
```bash
git tag plan-07-complete
git push origin plan-07-complete
```
- `[Linear]` Close all Linear issues in the `[agent]` project. Mark `[agent]` project complete and archive it. Update the permanent backlog feature issue to reflect delivery (link to completing PR, note what was delivered).
- `[Local]` Confirm every task in the ledger has reached `TASK_FINISHED`. Commit the final XML and ledger state. Update the permanent backlog feature issue (if tracked externally) or note delivery in the plan file.

### Completion with Loose Ends
- `[Linear]` Label unresolved issues `needs-triage`. Set `[agent]` project to **"In Review"**. Post a project update listing each open issue and why it's unresolved. Wait for human to review: they will promote worthy issues to the permanent backlog or discard them. Human marks the project complete and archives it.
- `[Local]` Leave unfinished tasks at their last valid milestone in the ledger (do not force a `TASK_FINISHED`). Note in the plan file which tasks are incomplete and why. Commit the XML and ledger. Wait for human to review.

### Abandonment
- Human commits a plan file deletion with a commit message referencing the superseding plan number
- You tag the deletion commit:
  ```bash
  git tag plan-07-abandoned
  git push origin plan-07-abandoned
  ```
- **Do not delete any earlier tags** (`plan-07-v1`, `plan-07-v2`, etc.) — they are the audit trail
- `[Linear]` Surface all open tasks for human triage. Migrate worthy tasks to the permanent backlog with a note: "Partial delivery — see plan-07-abandoned, superseded by plan-{M}". Archive the `[agent]` project with a closing note referencing the deletion commit hash and the superseding plan.
- `[Local]` Leave the ledger as-is — it is a permanent record of how far the plan got. Note the abandonment in the plan file's final commit. Commit the final XML and ledger state.

---

## Audit Trail

`[Linear]` Record in every Linear issue:

| Event | What to store in the issue |
|---|---|
| Task created | `github.com/.../blob/{plan-07-v1-hash}/.agents/plans/plan-07.md` |
| Task closed / obsoleted | `github.com/.../blob/{plan-07-vN-hash}/.agents/plans/plan-07.md` + one-line reason |

`[Local]` The ledger (`.agents/ledger.jsonl` + `.agents/objects/`) is the machine-readable audit trail; the XML file is the human-readable one. Both are versioned in git, so `git diff` between two plan tags shows exactly what changed. Each task's milestone sequence in the ledger — `TASK_CREATED` → `DE_RISK_FINISHED` → `HARDEN_FINISHED` → `TASK_FINISHED` — is the immutable record of its lifecycle, and every event is content-addressed so the trail cannot be silently rewritten.

Feature issues in the permanent backlog should accumulate references to every plan that contributed to them — this gives a full delivery history across the feature's lifetime.

---

## What Lives Where — Quick Reference

| Content | Location | Reason |
|---|---|---|
| Plan narrative, design decisions, references | `.agents/plans/*.md` in git | Needs diffs, version history, co-evolution with code |
| Task state (done / not done) | `[Linear]` Linear `[agent]` project · `[Local]` `.agents/plans/plan-{N}-tasks.xml` | Needs status tracking and human review |
| Machine-readable execution trace | `[Local]` `.agents/ledger.jsonl` + `.agents/objects/` | Content-addressed milestone history, TLA-checked |
| Feature definitions | `[Linear]` Linear permanent backlog · `[Local]` Noted in plan file Context section | Permanent, human-curated |
| Link between plan version and tasks | `[Linear]` Tag-based GitHub permalink in Linear issue description · `[Local]` plan version recorded in `TASK_CREATED` payload/metadata | Immutable, survives branch lifecycle |
| This workflow | `~/.claude/skills/${model.skillName()}/SKILL.md` | Loaded by agent at task start |
| Repo-specific overrides | `CLAUDE.md` in repo root | Workspace name, project conventions, etc. |

---
