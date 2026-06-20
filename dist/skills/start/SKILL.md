

# start — Agent Coding Workflow

## When to apply this skill
Apply this skill whenever you are:
- Starting work on a new feature or task
- Asked to write, revise, or execute a plan
- Picking up existing work from Linear
- Closing out, abandoning, or handing off a plan

---

## Core Invariants — Never Violate These

1. **Features vs Plans are strictly separate.** Feature issues live in the permanent backlog forever. Plan issues live in transient `[agent]` projects and are archived after completion. Never create feature issues inside an `[agent]` project.
2. **A committed, pushed, human-reviewed plan is the contract.** You execute against it. You do not autonomously modify it.
3. **Every plan must reference at least one permanent backlog feature issue.** `[Linear]` Create an `[agent]` project linking to it. `[Local]` Record it in the `<backlog-issue>` metadata element of the tasks XML file. If no backlog issue exists, stop and create one before proceeding.
4. **Task tracking is never the source of truth for plan content.** Git is. Linear (or the local tasks file) tracks task state only.
5. **Tags are permanent.** Never delete a plan version tag from remote, even on abandonment or squash merge.
6. **Tests precede implementation.** Write integration test(s) before any task code (Phase 2 preamble), then the unit test for each task before its implementation. Never implement without a failing test already committed. (Apply as far as possible — migrations and config may not be TDD-able.)

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

## Task Tracking Mode

This workflow supports two task tracking modes. Choose one at the start of each plan:

- **`[Linear]`** — Uses Linear issues and projects. Requires a Linear account and the Linear MCP server configured in Claude Code.
- **`[Local]`** — Uses a local XML file at `.agents/plans/plan-{N}-tasks.xml`. No external services required. Requires the plugin's SessionStart hook to have run (downloads the Java CLI runtime to `~/.cache/shipsmooth/`).

Throughout this skill, instructions marked `[Linear]` apply only in Linear mode; instructions marked `[Local]` apply only in Local mode. Unmarked instructions apply to both.

`[Local]` Script invocations use `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth <subcommand>`. All scripts read/write `.agents/plans/plan-{N}-tasks.xml` relative to the repo root.

---

## Repository Structure

```
.agents/
  plans/
    plan-07.md            # plan files live here, versioned in git
    plan-07-tasks.xml     # [Local] task state (sibling to plan file)
```

Plans are markdown files. They contain: narrative, design decisions, architecture notes, open questions, and references. Code never goes here.

---

## What Lives Where — Quick Reference

| Content | Location | Reason |
|---|---|---|
| Plan narrative, design decisions, references | `.agents/plans/*.md` in git | Needs diffs, version history, co-evolution with code |
| Task state (done / not done) | `[Linear]` Linear `[agent]` project · `[Local]` `.agents/plans/plan-{N}-tasks.xml` | Needs status tracking and human review |
| Feature definitions | `[Linear]` Linear permanent backlog · `[Local]` Noted in plan file Context section | Permanent, human-curated |
| Link between plan version and tasks | `[Linear]` Tag-based GitHub permalink in Linear issue description · `[Local]` `<created-from>` child element in XML | Immutable, survives branch lifecycle |
| This workflow | `~/.claude/skills/start/SKILL.md` | Loaded by agent at task start |
| Repo-specific overrides | `CLAUDE.md` in repo root | Workspace name, project conventions, etc. |

---

## Git Tagging Convention

Every time a plan file is committed, immediately create and push a version tag:

```bash
${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan tag --plan {N} --kind version
# prints: git push origin plan-{N}-v{K}  — run that line to push
```

On clean completion:
```bash
${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan tag --plan {N} --kind complete
# prints: git push origin plan-{N}-complete
```

On abandonment:
```bash
${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan tag --plan {N} --kind abandoned
# prints: git push origin plan-{N}-abandoned
```

Tag naming: `plan-{N}-v{version}` for iterations, `plan-{N}-complete` for clean closeout, `plan-{N}-abandoned` for abandonment. `plan tag --kind version` refuses to re-tag if the computed tag already exists — commit more changes first.

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

## Phase 0 — Intake

**First, check for an active plan — do not start a new one on top of it.**
Before treating any message as a fresh kickoff, look for a plan that is already
in flight. Glance at the plans on disk and their state — especially the **latest**
one:

- list `.agents/plans/plan-*-tasks.xml` (the highest plan number is the most
  likely candidate), and
- check that plan's state with
  `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan resume --plan {N}` — a plan-level status of `active` /
  `in-review` with tasks still `pending` / in-progress means work is unfinished.

If any plan looks active, **surface it as a question** before doing anything
else: name the plan and ask the user whether to continue it or deliberately
start a new one. Do not auto-create a new branch or plan file while a plan
appears to be in flight. *(This is a judgment call for now — there is no single
deterministic "is any plan active" check; tracked as a known gap. Lean toward
asking when unsure.)*

Once you have confirmed there is no active plan to resume, decide how much
context you actually have. The kickoff sets the mode for everything that
follows — choose it deliberately.

**The thin-vs-rich test.** Context is **thin** when *all three* hold:

- the kickoff message is short (roughly two sentences or fewer), **and**
- no spec, PRD, or plan body is attached, **and**
- there is no substantial planning earlier in this conversation.

If any one of these is absent, context is **rich** — skip to Phase 1.

### Thin context → quickstart, then hand back

A short kickoff means the user wants to move fast and iterate. He is signalling
that he will add detail later or work exploratorily. **Do not slow him down.**
Run **one** command and hand back:

```bash
${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan quick --desc "{short-description}"
# derives the next plan number, creates + checks out t/{N}-{slug},
# and writes a stub .agents/plans/plan-{N}.md.
# It does NOT commit — that is intentional.
```

Then relay the command's output to the user in one or two lines — the branch and
stub plan file now exist on the branch for him to flesh out — and **stop, return
control to the chat.**

`plan quick` owns the whole thin-path scaffold: plan-number derivation, branch
creation, and writing the stub file. **You do not author the plan file or run
git yourself.** In particular, **do not commit** what `plan quick` wrote — it
deliberately leaves the stub uncommitted so the user commits on his own terms
(and so a missing git identity can't strand the quickstart). There is no
follow-up step after `plan quick` on the thin path.

**Do not**, on the thin path:

- hand-author the stub plan file, then `git add`/`git commit` it — `plan quick`
  already wrote it and intentionally left it uncommitted; adding a commit is the
  exact mistake this path exists to prevent,
- run `git commit`, `git tag`, `git push`, or configure git identity,
- investigate the repository or read source files to "understand the feature",
- ask clarifying questions or present an options questionnaire,
- estimate per-task risk, run `plan init`, tag, or set up task tracking.

Those belong to the rich-context pass (Phase 1), reached once the user has
fleshed out the stub.

### Worked example (target vs. anti-target)

Kickoff: *"start a new plan, feature is X"* — no spec, no prior planning.

- ✅ **Target:** run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan quick --desc "X"` → relay its
  output (branch + stub created, uncommitted) → **stop**.
- ❌ **Anti-target #1:** run several rounds of repo investigation, then fire a
  multi-part questionnaire asking the user to choose the approach, before
  creating anything. This interrogates the user at the moment he wanted to move
  fast. *Do not do this.*
- ❌ **Anti-target #2:** after `plan quick` (or instead of it), hand-write the
  stub file and `git commit` it. The commit is unrequested git work that can
  fail on an unconfigured identity and strand the flow. *Do not do this.*

---

## Phase 1 — Plan, Calibrate, & Commit

This is the **rich-context** path, reached either directly when kickoff context
is already rich, or after the user has fleshed out a Phase 0 stub.

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
   ${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan preflight --plan {N}
   # Exits 0 (PASS) or 1 (FAIL: dirty tree / missing version tag). Warns on unpushed branch.
   ```
7. **Create Task Tracking Infrastructure:**
   - `[Linear]` Create the `[agent]` Linear project. Create Linear issues from the **risk-sorted** plan tasks. Each issue description must include the **Risk Level** ($L/M/H$) and the tag-based GitHub URL of the specific plan version that generated it.
   - `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan init --plan {N} --tasks-from .agents/plans/plan-{N}.md` to generate `.agents/plans/plan-{N}-tasks.xml`. Commit the XML file immediately after creation. **Never hand-write this XML file — always generate it via the CLI. The format uses child elements, not attributes.** The CLI requires task headings in the form `### Task N: Name [Risk]` where `N` is a positive integer — alphanumeric IDs (e.g. `01-A`) are not supported. To express a dependency between tasks, add a `*Depends-on: P[,Q...]*` line anywhere in the task body before the next heading (e.g. `*Depends-on: 1,3*`). The CLI parses this line and writes `<depends-on>` into the XML automatically.
   - Organise tasks as **thin vertical slices** in both modes.
8. **Final Review & Go-ahead:**
   - `[Linear]` **Stop.** Post to the Linear project that the risk-sorted plan is ready for review.
   - `[Local]` **Stop.** Tell the human the XML task file has been committed and the plan is ready for review.
   - **Wait for explicit human go-ahead before proceeding to Phase 2.**

---

## Phase 2 — Execute

**Session-resume pre-flight `[Local]`** — If you are picking up a plan that was started in a previous session, run this before doing anything else:

```bash
${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan resume --plan {N}
# Prints: XML file present check and task state summary.
```

Only proceed once you know which tasks are done and which are next.

---

**Step 0: Create a branch**

Create a branch named after the primary issue for this plan:
```bash
${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan branch --issue {issue-id} --desc "{short-description}"
# prints: git push -u origin t/{issue-id}-{slug}  — run that line to push
```
All task commits go on this branch. The `t/` prefix stands for "task". Usernames are omitted — the task identity is what matters long-term.

**Before writing any code**, confirm the test coverage threshold with the human (default: 95%). Record the agreed value before proceeding.

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

For every task in the risk-sorted sequence, apply the appropriate sub-phases:

#### High and Medium risk tasks — De-risk & Harden Cycle

##### Step A: De-risking (Spiral Phase)
- **Goal:** Validate logic and architectural direction. Ignore "Implementation Quality" rules.
- Write at least one failing test (and not more than 3) that targets the core logic (preserving 
Core Invariant #6).
- Implement just enough to prove the approach works. Focus on the core complexity.
- Commit as `draft(N): de-risk [task name]`.
- `[Linear]` Post a comment on the Linear issue notifying the human the draft is ready.
- `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task status --plan {N} --task {id} --status de-risked` and `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task comment --plan {N} --task {id} --message "De-risk draft ready for review"`.
- **Wait for explicit approval of the approach.**

##### Step B: Hardening (Quality Phase)
- **Goal:** Achieve technical excellence, human readability, and coverage threshold.
- Refactor the de-risked code for readability, performance, and project patterns. If skill 
"experimental-refine-dev" exists, then use it to improve the design.
- Follow Test Driven Development if possible: Write only one test at a time, then the implementing code 
and then refactor.
- Keep doing Step B until coverage meets the agreed threshold (and if "experimental-refine-dev" skill exists,
quality conforms to its instructions):
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
- `[Linear]` Mark the Linear issue **Agent Coded**.


- `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task status --plan {N} --task {id} --status agent-coded` and `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`.


#### Low risk tasks — Single-pass (current behavior)

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


   - `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task status --plan {N} --task {id} --status agent-coded` and `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`. No draft review needed.


---

- **Minor deviation** (task split, reorder, clarification):
  - `[Linear]` Update the Linear issue(s), add a deviation comment explaining why, continue.
  - `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task deviation --plan {N} --task {id} --type minor --message "..."`, continue.
- **Major deviation** (fundamental plan problem, architecture issue, blocked): Stop immediately.
  - `[Linear]` Post a Linear project update. Set project health to **"At Risk"**.
  - `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan update --plan {N} --blocked --message "..."`.
  - Wait for the human to revise the plan file, commit, push, and give a new go-ahead.

Never autonomously modify the `.agents/plans/` file during execution. If a plan change is needed, surface it and wait.

---

## Plan Closeout

### Clean Completion
```bash
git tag plan-07-complete
git push origin plan-07-complete
```
- `[Linear]` Close all Linear issues in the `[agent]` project. Mark `[agent]` project complete and archive it. Update the permanent backlog feature issue to reflect delivery (link to completing PR, note what was delivered).
- `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan update --plan {N} --status complete --message "Plan complete."`. Commit the final XML state. Update the permanent backlog feature issue (if tracked externally) or note delivery in the plan file.

### Completion with Loose Ends
- `[Linear]` Label unresolved issues `needs-triage`. Set `[agent]` project to **"In Review"**. Post a project update listing each open issue and why it's unresolved. Wait for human to review: they will promote worthy issues to the permanent backlog or discard them. Human marks the project complete and archives it.
- `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth task status --plan {N} --task {id} --status needs-triage` for each unresolved task. Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan update --plan {N} --status in-review --message "..."`. Commit the XML. Wait for human to review.

### Abandonment
- Human commits a plan file deletion with a commit message referencing the superseding plan number
- You tag the deletion commit:
  ```bash
  git tag plan-07-abandoned
  git push origin plan-07-abandoned
  ```
- **Do not delete any earlier tags** (`plan-07-v1`, `plan-07-v2`, etc.) — they are the audit trail
- `[Linear]` Surface all open tasks for human triage. Migrate worthy tasks to the permanent backlog with a note: "Partial delivery — see plan-07-abandoned, superseded by plan-{M}". Archive the `[agent]` project with a closing note referencing the deletion commit hash and the superseding plan.
- `[Local]` Run `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/0.3.25/bin/shipsmooth plan update --plan {N} --status abandoned --message "Superseded by plan-{M}."`. Commit the final XML state.

---

## Audit Trail

`[Linear]` Record in every Linear issue:

| Event | What to store in the issue |
|---|---|
| Task created | `github.com/.../blob/{plan-07-v1-hash}/.agents/plans/plan-07.md` |
| Task closed / obsoleted | `github.com/.../blob/{plan-07-vN-hash}/.agents/plans/plan-07.md` + one-line reason |

`[Local]` The XML file is the audit trail. `<created-from>` and `<closed-at-version>` child elements on each `<task>` serve the same role. The XML is versioned in git, so `git diff` between two plan tags shows exactly what changed.

If the creation version equals the closeout version, the plan never changed during execution. If they differ, the git diff between the two tag hashes shows exactly what changed and why.

Feature issues in the permanent backlog should accumulate references to every plan that contributed to them — this gives a full delivery history across the feature's lifetime.

---
