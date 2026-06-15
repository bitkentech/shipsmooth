@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Phase 0 — Intake

**First, check for an active plan — do not start a new one on top of it.**
Before treating any message as a fresh kickoff, look for a plan that is already
in flight. Glance at the plans on disk and their state — especially the **latest**
one:

- list `.agents/plans/plan-*-tasks.xml` (the highest plan number is the most
  likely candidate), and
- check that plan's state with
  `${model.cliBin()} plan resume --plan {N}` — a plan-level status of `active` /
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

### Thin context → fast-start, then hand back

A short kickoff means the user wants to move fast and iterate. He is signalling
that he will add detail later or work exploratorily. **Do not slow him down.**
Do exactly this, in one shot:

1. Create the branch:
   ```bash
   ${model.cliBin()} plan branch --issue {issue-id} --desc "{short-description}"
   # prints: git push -u origin t/{issue-id}-{slug}  — run that line to push
   ```
2. Write a **stub** `.agents/plans/plan-{N}.md` on the branch with this skeleton:
   - a title line,
   - a `## Context` placeholder (one line noting the feature in the user's own
     words; mark unknowns explicitly),
   - a `## Tasks` section containing only a notional placeholder,
   - a clear note at the top that this is a stub for the user to flesh out.
3. Commit the stub to the branch.
4. Tell the user — in one or two lines — that the branch and a basic plan file
   now exist on the branch for his use. **Then stop and return control to the
   chat.**

**Do not**, on the thin path:

- investigate the repository or read source files to "understand the feature",
- ask clarifying questions or present an options questionnaire,
- estimate per-task risk, run `plan init`, tag, or set up task tracking.

Those belong to the rich-context pass (Phase 1), reached once the user has
fleshed out the stub.

### Worked example (target vs. anti-target)

Kickoff: *"start a new plan-{N}, feature is X"* — no spec, no prior planning.

- ✅ **Target:** create the branch → write the stub `plan-{N}.md` → commit →
  tell the user both exist on the branch → **stop**.
- ❌ **Anti-target:** run several rounds of repo investigation, then fire a
  multi-part questionnaire asking the user to choose the approach, before
  creating anything. This interrogates the user at the exact moment he wanted
  to move fast. *Do not do this.*

---
