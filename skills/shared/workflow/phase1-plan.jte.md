@import io.bitken.ss.resources.PluginModel
@param PluginModel model
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
