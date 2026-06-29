@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Phase 2 — Execute

**Session-resume pre-flight** — If you are picking up a plan that was started in a previous session, run this before doing anything else:

```bash
${model.cliBin()} plan resume --plan {N}
# Prints: XML file present check and task state summary.
```

Only proceed once you know which tasks are done and which are next.

**Where the plan files live** — Do not assume plan narratives are under
`.shipsmooth/plans/` in the project repo. In **separate-dir** mode (the default) the project
repo stays untouched and the plan files live in a separate state directory. Ask the CLI
where to read them — it is the source of truth — rather than guessing:

```bash
${model.cliBin()} store info --json
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
${model.cliBin()} plan branch --issue {issue-id} --desc "{short-description}"
# prints: git push -u origin t/{issue-id}-{slug}  — run that line to push
```
All task commits go on this branch. The `t/` prefix stands for "task". Usernames are omitted — the task identity is what matters long-term.

**Before writing any code**, confirm the test coverage threshold with the human (default: 95%). Record the agreed value before proceeding.

@template.shared.workflow.commit-message-convention(model = model)

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
Core Invariant #6).
- Implement just enough to prove the approach works. Focus on the core complexity.
- Commit per the **commit-message convention**: `draft(N): de-risk [task name]` in same-repo storage; in standalone (separate-dir) storage a plain feature message with no `draft(N)`/`task(N)` reference.
- Run `${model.cliBin()} task status --plan {N} --task {id} --status de-risked` and `${model.cliBin()} task comment --plan {N} --task {id} --message "De-risk draft ready for review"`.
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
- Commit the completed task (tests + implementation), wording the message per the **commit-message convention** (standalone → plain feature message, no `task(N)` prefix):
  ```bash
  git commit -m "task(N): <short description>"   # same-repo storage; standalone (separate-dir): plain feature message
  git push origin t/{issue-id}-{short-description}
  ```
  This creates a stable rollback point. A human reviewing the PR can check out this commit to inspect each task in isolation.
@if(model.isCodex())
@template.shared.workflow.codex.set-commit-hardening(model = model)
@elseif(model.isGemini())
@template.shared.workflow.gemini.set-commit-hardening(model = model)
@else
@template.shared.workflow.claude.set-commit-hardening(model = model)
@endif

#### Low risk tasks — Single-pass (current behavior)

1. Write the unit test(s) for this task. Commit them failing (red).
2. Implement the task. Run tests until green.
3. Check coverage meets the agreed threshold:
   ```bash
   # example — adjust to your toolchain:
   npm test -- --coverage --coverageThreshold='{"global":{"lines":95}}'
   ```
   Do not proceed until coverage passes.
4. Commit the completed task (tests + implementation), wording the message per the **commit-message convention** (standalone → plain feature message, no `task(N)` prefix):
   ```bash
   git commit -m "task(N): <short description>"   # same-repo storage; standalone (separate-dir): plain feature message
   git push origin t/{issue-id}-{short-description}
   ```
   - No draft review needed.
@if(model.isCodex())
@template.shared.workflow.codex.set-commit-low-risk(model = model)
@elseif(model.isGemini())
@template.shared.workflow.gemini.set-commit-low-risk(model = model)
@else
@template.shared.workflow.claude.set-commit-low-risk(model = model)
@endif

---

- **Minor deviation** (task split, reorder, clarification):
  - Run `${model.cliBin()} task deviation --plan {N} --task {id} --type minor --message "..."`, continue.
- **Major deviation** (fundamental plan problem, architecture issue, blocked): Stop immediately.
  - Run `${model.cliBin()} plan update --plan {N} --blocked --message "..."`.
  - Wait for the human to revise the plan file, commit, push, and give a new go-ahead.

Never autonomously modify the `.shipsmooth/plans/` file during execution. If a plan change is needed, surface it and wait.

---
