@import io.bitken.ss.resources.PluginModel
@param PluginModel model
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
   - `[Local]` Run `${model.cliBin()} plan init --plan {N} --tasks-from .agents/plans/plan-{N}.md` to generate `.agents/plans/plan-{N}-tasks.xml`. Commit the XML file immediately after creation. **Never hand-write this XML file — always generate it via the CLI. The format uses child elements, not attributes.** The CLI requires task headings in the form `### Task N: Name [Risk]` where `N` is a positive integer — alphanumeric IDs (e.g. `01-A`) are not supported. To express a dependency between tasks, add a `*Depends-on: P[,Q...]*` line anywhere in the task body before the next heading (e.g. `*Depends-on: 1,3*`). The CLI parses this line and writes `<depends-on>` into the XML automatically.
   - Organise tasks as **thin vertical slices** in both modes.
8. **Final Review & Go-ahead:**
   - `[Linear]` **Stop.** Post to the Linear project that the risk-sorted plan is ready for review.
   - `[Local]` **Stop.** Tell the human the XML task file has been committed and the plan is ready for review.
   - **Wait for explicit human go-ahead before proceeding to Phase 2.**

---
