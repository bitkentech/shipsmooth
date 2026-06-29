@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Phase 0 — Intake

**First, check for an active plan — do not start a new one on top of it.**
Before treating any message as a fresh kickoff, look for a plan that is already
in flight. Glance at the plans on disk and their state — especially the **latest**
one:

- find the plans directory first — in **filesystem** mode (the default) it is not
  `.shipsmooth/plans/` but a separate state dir. Ask the CLI:
  `${model.cliBin()} store info --json` reports `plansDir` (when `status` is `ready`).
  List `plansDir`'s `plan-*-tasks.xml` (the highest plan number is the most likely
  candidate); if `status` is **not** `ready`, state is not set up yet — run the
  **first-run handshake** below before going further (there is no active plan to resume).
- check that plan's state with
  `${model.cliBin()} plan resume --plan {N}` — a plan-level status of `active` /
  `in-review` with tasks still `pending` / in-progress means work is unfinished.

@template.shared.workflow.first-run-handshake(model = model)

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
${model.cliBin()} plan quick --desc "{short-description}"
# derives the next plan number, creates + checks out t/{N}-{slug},
# and writes a stub .shipsmooth/plans/plan-{N}.md.
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

- ✅ **Target:** run `${model.cliBin()} plan quick --desc "X"` → relay its
  output (branch + stub created, uncommitted) → **stop**.
- ❌ **Anti-target #1:** run several rounds of repo investigation, then fire a
  multi-part questionnaire asking the user to choose the approach, before
  creating anything. This interrogates the user at the moment he wanted to move
  fast. *Do not do this.*
- ❌ **Anti-target #2:** after `plan quick` (or instead of it), hand-write the
  stub file and `git commit` it. The commit is unrequested git work that can
  fail on an unconfigured identity and strand the flow. *Do not do this.*

---
