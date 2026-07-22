---
description: Use when starting any task — applies the shipsmooth agent coding workflow.
disable-model-invocation: true
---

## When to apply this skill
Apply this skill whenever you are:
- Starting work on a new feature or task
- Asked to write, revise, or execute a plan
- Picking up existing in-flight plan work
- Closing out, abandoning, or handing off a plan

---

## Core Invariants — Never Violate These

1. **A committed, pushed, human-reviewed plan is the contract.** You execute against it. You do not autonomously modify it.
2. **Every plan must reference at least one permanent backlog feature.** Record it in the `<backlog-issue>` metadata element of the tasks XML file. If no backlog feature exists, stop and create one before proceeding.
3. **Task tracking is never the source of truth for plan content.** Git is. The local tasks file tracks task state only.
4. **Tags are permanent.** Never delete a plan version tag from remote, even on abandonment or squash merge.
5. **Tests precede implementation.** Write integration test(s) before any task code (Phase 2 preamble), then the unit test for each task before its implementation. Never implement without a failing test already committed. (Apply as far as possible — migrations and config may not be TDD-able.)

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

## Task Tracking

Task state is tracked in a local XML file at `<plansDir>/plan-{N}-tasks.xml` (see Repository Structure for `<plansDir>`). No external services required. This requires the plugin's SessionStart hook to have run (downloads the Java CLI runtime to `~/.cache/shipsmooth/`).

Script invocations use the shipsmooth CLI. Throughout this skill it is abbreviated
as **`$SS`**, defined once here:

```bash
SS="${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/0.3.36/bin/shipsmooth"
```

Every `$SS <subcommand>` below means that path — expand it when you actually run the
command. All scripts read/write `<plansDir>/plan-{N}-tasks.xml`.

---

## Repository Structure

Plan files and task state live in the **plans directory** — written `<plansDir>`
throughout this skill. Ask the CLI for its real location with `$SS store info --json`
(the `plansDir` field); it is the source of truth. In **separate-dir** mode (the
default) `<plansDir>` is a folder in a separate state directory, leaving the project
repo untouched. In **same-repo** mode it resolves to `.shipsmooth/plans/` inside the
repo.

```
<plansDir>/
  plan-07.md            # plan files live here, versioned in git
  plan-07-tasks.xml     # task state (sibling to plan file)
```

Plans are markdown files. They contain: narrative, design decisions, architecture notes, open questions, and references. Code never goes here.

---

## What Lives Where — Quick Reference

| Content | Location | Reason |
|---|---|---|
| Plan narrative, design decisions, references | `<plansDir>/plan-*.md` in git | Needs diffs, version history, co-evolution with code |
| Task state (done / not done) | `<plansDir>/plan-{N}-tasks.xml` | Needs status tracking and human review |
| Feature definitions | Noted in plan file Context section | Permanent, human-curated |
| Link between plan version and tasks | `<created-from>` child element in XML | Immutable, survives branch lifecycle |
| Repo-specific overrides | `CLAUDE.md` in repo root | Workspace name, project conventions, etc. |

---

## Git Tagging Convention

Tag every plan-file commit and every closeout. For the exact `$SS plan tag --kind version|complete|abandoned` commands, the tag-naming rules, and the re-tag guard, read **`reference/git-tagging.md`** (in this skill's directory) when you commit a plan file or close out a plan.

---

## Phase 0 — Intake

**First, check for an active plan — do not start a new one on top of it.**
Before treating any message as a fresh kickoff, look for a plan that is already
in flight. Glance at the plans on disk and their state — especially the **latest**
one:

- find the plans directory first — in **separate-dir** mode (the default) it is not
  `.shipsmooth/plans/` but a separate state dir. Ask the CLI:
  `$SS store info --json` reports `plansDir` (when `status` is `ready`).
  List `plansDir`'s `plan-*-tasks.xml` (the highest plan number is the most likely
  candidate); if `status` is **not** `ready`, state is not set up yet — run the
  **first-run handshake** below before going further (there is no active plan to resume).
- check that plan's state with
  `$SS plan resume --plan {N}` — a plan-level status of `active` /
  `in-review` with tasks still `pending` / in-progress means work is unfinished.

> **First-run handshake.** If a `$SS` command reports state is not set up — `store info --json` returns a `status` other than `ready`, or a state-dependent command exits with a `status:"needs-decision"` / `status:"unresolvable"` JSON line (exit 10 / 11) — do **not** treat it as a normal error. Read **`reference/first-run-handshake.md`** (in this skill's directory) and follow it: present the CLI's `prompt` verbatim, wait for a real human choice, then re-invoke `store init` to act on it. The CLI never prompts on stdin, so running this handshake is the skill's job.

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
$SS plan quick --desc "{short-description}"
# derives the next plan number, creates + checks out t/{N}-{slug},
# and writes a stub plan-{N}.md in the plans dir (<plansDir>).
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

For a concrete target / anti-target walk-through of the thin path (the one-command quickstart vs. the "investigate-then-interrogate" and "hand-write-then-commit" anti-patterns), read **`reference/phase0-worked-example.md`**.

---

## Phase 1 — Plan, Calibrate, & Commit

This is the **rich-context** path, reached either directly when kickoff context
is already rich, or after the user has fleshed out a Phase 0 stub.

**You do not write or run any implementation code during this phase.**

1. **Draft Plan:** Write or update the plan file at `<plansDir>/plan-{N}.md`.
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
   git add <plansDir>/plan-07.md
   git commit -m "plan(07): risk-calibrated plan for [short-description]"
   git push origin t/{issue-id}-{short-description}
   ```
   Then tag this plan version manually (see Git Tagging Convention):
   ```bash
   $SS plan tag --plan {N} --kind version
   # prints: git push origin plan-{N}-v{K}  — run that line to push the tag
   ```
6. **Verify Preconditions:**
   ```bash
   $SS plan preflight --plan {N}
   # Exits 0 (PASS) or 1 (FAIL: dirty tree / missing version tag). Warns on unpushed branch.
   ```
7. **Create Task Tracking Infrastructure:**
   - Run `$SS plan init --plan {N} --tasks-from <plansDir>/plan-{N}.md` to generate `<plansDir>/plan-{N}-tasks.xml`. Commit the XML file immediately after creation. **Never hand-write this XML file — always generate it via the CLI.** Each task in the plan file must use exactly this grammar — an h3 heading `### Task N: Short task name [High|Medium|Low]` (numeric `N`, colon after it, risk tag in square brackets), optionally followed by `*Depends-on: 1,2*` as the first body line after the heading. The CLI validates what it parsed: if no heading matches, `plan init` fails with an error stating the expected grammar, and it lists any near-miss heading or depends-on lines it skipped, with line numbers — fix what it reports rather than guessing the exact syntax.
   - Organise tasks as **thin vertical slices**.
8. **Final Review & Go-ahead:**
   - **Stop.** Tell the human the XML task file has been committed and the plan is ready for review.
   - **Wait for explicit human go-ahead before proceeding to Phase 2.**

---

## Phase 2 — Execute

**Session-resume pre-flight** — If you are picking up a plan that was started in a previous session, run this before doing anything else:

```bash
$SS plan resume --plan {N}
# Prints: XML file present check and task state summary.
```

Only proceed once you know which tasks are done and which are next.

**Where the plan files live** — Do not assume plan narratives are under
`.shipsmooth/plans/` in the project repo. In **separate-dir** mode (the default) the project
repo stays untouched and the plan files live in a separate state directory. Ask the CLI
where to read them — it is the source of truth — rather than guessing:

```bash
$SS store info --json
# -> {"status":"ready","storageType":"separate-dir","stateRoot":"...","plansDir":"<dir>/plans"}
#    Read plan narratives (plan-{N}.md) and task XML from the reported `plansDir`.
#    If status is not "ready", state is not set up yet — handle per first-run (Phase 0).
```

Load the plan narrative for `{N}` from the reported `plansDir` before executing, the same
as you would for a same-repo plan — `storageType: same-repo` simply reports the in-repo `plansDir`.

---

**Step 0: Create a branch**

Create a branch named after the primary issue for this plan:
```bash
$SS plan branch --issue {issue-id} --desc "{short-description}"
# prints: git push -u origin t/{issue-id}-{slug}  — run that line to push
```
All task commits go on this branch. The `t/` prefix stands for "task". Usernames are omitted — the task identity is what matters long-term.

**Before writing any code**, confirm the test coverage threshold with the human (default: 95%). Record the agreed value before proceeding.


> **Commit-message convention (code commits in the project repo).** How you word a code
> commit depends on the resolved storage type. Check it once per session with
> `$SS store info --json` and read `storageType`.
>
> - **`same-repo` storage:** keep the prefixed convention — `task(N): <short description>` and
>   `draft(N): de-risk <task name>`. The plan/task history is shipsmooth's own and lives
>   alongside the code, so the prefixes are welcome.
> - **`separate-dir` (standalone) storage:** the project repo must stay **zero-trace**. Write
>   plain, feature-oriented messages with **no `plan(N)`/`task(N)`/`draft(N)` prefix** and
>   no plan or task references — e.g. `Add retry to upload client`, not
>   `task(3): add retry`. This applies to **every** project-repo commit, including the
>   preamble integration-test commit (write `Add end-to-end test for <feature>`, not a
>   `plan(N)`-referencing message).
>
> Traceability is **not lost** in standalone mode: the task↔commit link lives in the state
> repo's task XML, recorded via `task set-commit`. State-repo commits (the plan file and
> task XML — the `plan(N): …` commit) keep full plan/task info; that history is shipsmooth's
> own and invisible to the user. This convention governs only the **project repo's** code
> commits.


### Preamble: integration tests (once, before any task)

1. Write 1–2 integration tests that exercise the feature end-to-end. No more than two.
2. Commit and push them with no implementation — they must fail (red). Word the commit message per the **commit-message convention** above (in standalone mode, no `plan(N)`/`task(N)` reference — this is a project-repo commit too).
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
the "Tests precede implementation" invariant).
- Implement just enough to prove the approach works. Focus on the core complexity.
- Commit per the **commit-message convention**: `draft(N): de-risk [task name]` in same-repo storage; in standalone (separate-dir) storage a plain feature message with no `draft(N)`/`task(N)` reference.
- Run `$SS task status --plan {N} --task {id} --status de-risked` and `$SS task comment --plan {N} --task {id} --message "De-risk draft ready for review"`.
- **Wait for explicit approval of the approach.**

##### Step B: Hardening (Quality Phase)
- **Goal:** Achieve technical excellence, human readability, and coverage threshold.
- Refactor the de-risked code for readability, performance, and project patterns. If skill 
"experimental-refine-dev" exists, then use it to improve the design.
- Follow Test Driven Development if possible: Write only one test at a time, then the implementing code 
and then refactor.
- Keep doing Step B until coverage meets the threshold agreed in Step 0 (and if
"experimental-refine-dev" skill exists, quality conforms to its instructions). Use your
project's own coverage command to check.
- Commit the completed task (tests + implementation), wording the message per the **commit-message convention** (standalone → plain feature message, no `task(N)` prefix):
  ```bash
  git commit -m "task(N): <short description>"   # same-repo storage; standalone (separate-dir): plain feature message
  git push origin t/{issue-id}-{short-description}
  ```
  This creates a stable rollback point. A human reviewing the PR can check out this commit to inspect each task in isolation.


- Run `$SS task status --plan {N} --task {id} --status agent-coded` and `$SS task set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`.


#### Low risk tasks — Single-pass (current behavior)

1. Write the unit test(s) for this task. Commit them failing (red).
2. Implement the task. Run tests until green.
3. Check coverage meets the threshold agreed in Step 0, using your project's own
   coverage command. Do not proceed until it passes.
4. Commit the completed task (tests + implementation), wording the message per the **commit-message convention** (standalone → plain feature message, no `task(N)` prefix):
   ```bash
   git commit -m "task(N): <short description>"   # same-repo storage; standalone (separate-dir): plain feature message
   git push origin t/{issue-id}-{short-description}
   ```
   - No draft review needed.


   - Run `$SS task status --plan {N} --task {id} --status agent-coded` and `$SS task set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`. No draft review needed.


---

- **Minor deviation** (task split, reorder, clarification):
  - Run `$SS task deviation --plan {N} --task {id} --type minor --message "..."`, continue.
- **Major deviation** (fundamental plan problem, architecture issue, blocked): Stop immediately.
  - Run `$SS plan update --plan {N} --blocked --message "..."`.
  - Wait for the human to revise the plan file, commit, push, and give a new go-ahead.

Never autonomously modify the plan file (in `<plansDir>`) during execution. If a plan change is needed, surface it and wait.

---

## Plan Closeout

When a plan reaches its end — clean completion, completion with loose ends, or abandonment — read **`reference/plan-closeout.md`** (in this skill's directory) for the exact per-case steps: the `agent-coded` → `closed` sweep, `needs-triage` for loose ends, the abandonment tag flow, and the final `$SS plan update --status …` call. It is end-of-plan reference, not needed during execution.

---


## Audit Trail

The XML task file is the audit trail. When you need to explain what changed during a
plan — for a review, a closeout note, or a post-mortem — read **`reference/audit-trail.md`**
(in this skill's directory) for how `<created-from>` / `<closed-at-version>` and the
git-tag diffs reconstruct the full history. It is reference-only, not needed to execute a
plan, so it is kept out of this always-loaded core.

---