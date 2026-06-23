# Plan 17 — Demo GIF for README (PB-261)

**Status:** DRAFT
**Linear backlog issue:** [PB-261](https://linear.app/pb-default/issue/PB-261/media-addition-for-docs-demo-gif-for-readme) (code-flow Skill project)
**Branch:** `t/pb-261-readme-demo-gif`
**Task tracking:** Local

---

## Context

The devostat README has no visual demo. A user landing on the GitHub page has no quick way to understand what a devostat session looks like. A first-pass animated GIF (`docs/demo-small.gif`) is already rendered and embedded in `README.md`, but the source `docs/demo.cast` has weaknesses:

- Recording starts with the `$ claude` invocation and startup animation (wastes the first ~1s)
- The skill load command shown is `/devostat` instead of the correct `/devostat:devostat`
- Recording ends before the first task in the demo plan completes, so the "closure" of the workflow isn't visible

This plan applies a small set of minor edits to the existing cast, re-renders the GIF, and merges to `main` as a cosmetic improvement. Deeper content improvements (showing plan-changing, richer session content) are deferred — the same branch will stay alive for follow-up work before a second merge.

**TDD note:** The work is content editing (a recorded terminal session + rendered GIF), not code. Core Invariant #6 (tests precede implementation) doesn't apply here — verification is visual inspection of the rendered GIF and README preview. This is recorded here as a minor deviation to the standard workflow.

**v6 revision note:** Tasks 5 and 7 subsumed into Task 6 after a screenplay design pass on 2026-04-14. Task 6 retargeted from "extend existing cast" to "rewrite cast around the real plan-13 v1→v2→v3 pivot arc" preserved in `docs/session/`. See the Task 6 section for the full screenplay and locked decisions. Task numbering is not renumbered — the gaps left by deleted Tasks 5 and 7 are the audit trail.

**v7 revision note (2026-04-15):** Added Task 12 — preserve the pre-pivot `demo.cast` (Next.js todo-app version, from commit `70bea21`) under `docs/demo-1/demo.cast`. Source-only; the old GIF is re-renderable from the cast. The README continues to use the new plan-13 pivot cast/GIF. Pure addition, no retarget or subsume.

---

## Tasks

### Task 1: Edit demo.cast [Medium]

Apply three content fixes directly to the JSON-per-line `docs/demo.cast` file:

1. **Trim the opening.** Remove events showing the `$ claude` invocation and Claude Code startup animation. Cast should begin with Claude Code already loaded and ready at the prompt. Re-base timestamps so the first remaining event is at `0.0`.
2. **Fix the skill-load command.** Where the cast shows `/devostat` being entered, change it to `/devostat:devostat` and update the corresponding echo/acknowledgement output.
3. **Extend end point (conditional).** If the current cast ends before the first task in the demo plan completes, extend the recording up to task completion. Only keep this extension if the re-rendered GIF stays under ~2MB; otherwise trim back to ~30s.

Medium risk because hand-editing a `.cast` file (preserving event structure, timestamps, ANSI escape sequences) has a moderate chance of breaking playback. Must verify by rendering.

### Task 2: Re-render demo-small.gif [Low]

Run the proven render pipeline:

```bash
agg --theme github-light --fps-cap 10 --idle-time-limit 2 \
    --font-size 16 --rows 15 \
    docs/demo.cast docs/demo-small.gif
```

Verify:
- File opens and plays correctly
- Text legible at 1:1 zoom in browser
- File size <2MB (ideally ~1-1.5MB)

If size exceeds 2MB with the extended end point, fall back: re-trim the cast to ~30s, re-render.

### Task 3: Commit and land on main [Low]

Commit only the three relevant files to `t/pb-261-readme-demo-gif`:
- `README.md` (GIF embed already staged)
- `docs/demo.cast` (edited source)
- `docs/demo-small.gif` (rendered output)

Do **not** commit scratch artifacts: `docs/demo-30s.cast`, `docs/demo-30s.gif`, `docs/demo-640.gif`.

Push, then merge to `main` (fast-forward or squash — user chooses at merge time). Keep the branch alive for deeper content overhaul work in a future pass.

### Task 4: Reorder README — one-liner before GIF [Low]

Move the one-liner description (`A Claude Code plugin that enables...`) to appear before the GIF embed, so the reading order is: title → tagline → one-liner → GIF.

```markdown
# devostat

*Agentic energy, channelled with skill.*

A Claude Code plugin that enables a plan-driven, risk-prioritised, checkpoint based, agentic coding workflow.

![devostat demo](docs/demo-small.gif)
```

Commit, push, merge to main.

### Task 5: *(subsumed into Task 6 at plan-17-v6 — see Task 6 section)*

### Task 6: Rewrite demo.cast around plan-13 pivot narrative [Medium]

The existing `docs/demo.cast` (fictional Next.js frontend for a todo app) does not demonstrate devostat's most distinctive feature: the plan-as-contract cycle where a user pauses execution, modifies the plan file, bumps the version tag, and resumes against the new version. Rather than extend it, replace it wholesale with a cast authored around the real plan-13 v1→v2→v3 pivot arc preserved in `docs/session/`.

Target: 30–40 second cast, rendering to a <2MB GIF via the existing `agg` pipeline.

**Source material** (in-repo, already scanned and committed):
- `docs/session/ac2b52af-…jsonl` — v1 draft + in-session v1→v2 nudge
- `docs/session/eb521172-…jsonl` — next-morning v2→v3 pivot
- `docs/session/9680eb1a-…jsonl` — post-v3 execution (not used in cast)

#### Screenplay — 3 beats, ~35 seconds

**Beat 0 — Opening (0 → 4s).** Skill already loaded. Cast opens with Claude Code ready at the prompt; no startup animation.

| t (s) | Screen content |
|---|---|
| 0.0 | `>` user typed: `/devostat:devostat continue plan-13` |
| 0.8 | `✓ devostat skill loaded` |
| 1.2 | dim: `Plan 13 v1 · 10 tasks · Task 9 in progress (agent-coded)` |
| 1.8 | dim: `Branch: t/pb-???-claude-code-plugin · on plan-13-v1 @ 45506c8` |
| 2.5 | dim: `Resuming Task 10 — "Verify mvn package contents"` |

**Beat 1 — v1→v2 in-session nudge (4 → 16s).** Real material from `ac2b52af` L528–L576 (2026-04-09 18:39–18:44 IST).

| t (s) | Screen content |
|---|---|
| 4.0 | `> Do mvn clean and then process-resources` |
| 4.8 | `$ mvn clean process-resources -q` |
| 5.8 | dim: `[INFO] BUILD SUCCESS` |
| 6.2 | `$ ls target/classes/` |
| 6.6 | output: `plugin.xml  tsconfig.json  META-INF/` |
| 7.3 | highlighted: `⚠  Wait — tsconfig.json shouldn't be in target/classes/` |
| 8.2 | yellow: `⏸  Execution paused for plan review` |
| 9.0 | `> I don't want to package tsconfig.json by mistake and leak info.` |
| 9.6 | `> Use code-flow workflow to update the plan — add a task to` |
| 9.9 | `  move package.json to src/main/node/ so the resource copy` |
| 10.2 | `  doesn't sweep tsconfig.json into the jar.` |
| 11.0 | `> This is a plan version update. Proceed accordingly.` |
| 12.0 | `$ $EDITOR .agents/plans/plan-13.md` |
| 12.5 | dim: `adding Task 10 — "Fix tsconfig.json leak (move package.json)"` |
| 13.2 | `$ git add .agents/plans/plan-13.md` |
| 13.6 | `$ git commit -m "plan(13): v2 — prevent tsconfig.json leak into jar"` |
| 14.2 | `$ git push` |
| 14.6 | dim: `[lefthook] auto-tag-plans: plan-13-v2` |
| 14.9 | green: `✓ Plan tagged plan-13-v2 and pushed` |
| 15.6 | green: `✓ Execution resumed on plan-13-v2` |

**Transition slide A (16 → 17.5s).** Full-width dim separator, held ~1.5s:

```
───  End of session · 2026-04-09 18:50 IST  ───
```

Then ~1s:

```
───  Next morning · 2026-04-10 10:28 IST  ───
```

**Beat 2 — v2→v3 pivot (17.5 → 32s).** Real material from `eb521172` L219–L272 (2026-04-10 11:16–11:38 IST).

| t (s) | Screen content |
|---|---|
| 17.5 | `>` user typed: `/devostat:devostat continue plan-13` |
| 18.0 | dim: `Plan 13 v2 · Resuming at Task 10` |
| 18.8 | `> I think we need to re-arrange this project as a Java mvn` |
| 19.1 | `  project that also has a Node/TypeScript component.` |
| 19.5 | `> I explored multi-module Maven last night. Let me share:` |
| 20.0 | dim: `(pasted from Google Gemini)` |
| 20.4 | dim paste: `  plugin-node/       Node build (TS → dist/)` |
| 20.7 | dim paste: `  plugin-resources/  XML, POMs, Java-side resources` |
| 21.0 | dim paste: `  plugin-dist/       assembly module — produces final jar` |
| 22.0 | `> This is a bigger change than v2. Plan it as v3.` |
| 22.8 | `> Use code-flow workflow. Create a new version of the plan.` |
| 24.0 | `$ $EDITOR .agents/plans/plan-13.md` |
| 24.6 | dim: `restructure → 3 maven modules; Tasks 10–12 rewritten` |
| 25.5 | `$ git add .agents/plans/plan-13.md` |
| 25.9 | `$ git commit -m "plan(13): v3 — restructure as multi-module Maven project"` |
| 26.5 | `$ git push` |
| 26.9 | dim: `[lefthook] auto-tag-plans: plan-13-v3` |
| 27.2 | green: `✓ Plan tagged plan-13-v3 and pushed` |
| 28.0 | dim: `Old tasks 10, 11, 12 closed at plan-13-v2` |
| 28.6 | dim: `New tasks 10, 11, 12, 13 created from plan-13-v3` |
| 29.5 | green: `✓ Execution resumed on plan-13-v3` |
| 30.5 | `─── Task 10 [HIGH] — set up plugin-node/ module ───` |
| 31.3 | `$ mkdir plugin-node && mv src/main/node/* plugin-node/` |
| 31.8 | dim ellipsis: `…` |

**Beat 3 — Closeout (32 → 35s).**

| t (s) | Screen content |
|---|---|
| 32.5 | `$ git log --oneline --tags=plan-13-*` |
| 33.0 | `b9e12d1 (tag: plan-13-v3) plan(13): v3 — multi-module Maven` |
| 33.2 | `8836bc8 (tag: plan-13-v2) plan(13): v2 — tsconfig.json leak fix` |
| 33.4 | `45506c8 (tag: plan-13-v1) plan(13): claude-code-plugin bootstrap` |
| 34.5 | End (viewer sees the three-tag audit trail). |

#### Decisions (locked 2026-04-14)

1. **Real IST timestamps on screen.** They cross-reference the preserved transcripts in `docs/session/`.
2. **Pasted block in Beat 2** is labelled `(pasted from Google Gemini)` and shows only 3 dim excerpt lines (~1s total screen time).
3. **Stay true to actual devostat UX.** No aspirational affordances. Every on-screen line must be something the current skill produces or a human would plausibly type.
4. **Editor beats** use the `$EDITOR .agents/plans/plan-13.md` → dim summary → save idiom at both t=12.0 and t=24.0. No fake editor UI flash.

#### Implementation

1. Hand-author `docs/demo.cast` as asciinema v2 (JSON-per-line) matching the timing table above. Discard the current file entirely.
2. Re-render:
   ```
   agg --theme github-light --fps-cap 10 --idle-time-limit 2 \
       --font-size 16 --rows 15 \
       docs/demo.cast docs/demo-small.gif
   ```
3. Visual QA: open `docs/demo-small.gif` in Firefox. Verify <2MB, legible text at 1:1, and all three plan-13 tags visible in the final frame.
4. Commit both files together: `task(6): rewrite demo around plan-13 pivot`.

This task may not land in one session. The screenplay is the contract — any deviation during authoring is a Task 6 deviation comment, not silent improvisation.

### Task 7: *(subsumed into Task 6 at plan-17-v6 — see Task 6 section)*

### Task 8: Link plan + tasks file from README [Low]

Add a link in the README pointing to the plan and task files that the demo GIF is based on, so viewers can inspect a concrete example.

Decision deferred to execution time:
- **Option A:** Create illustrative `docs/demo/plan-demo.md` + `plan-demo-tasks.xml` matching the GIF content
- **Option B:** Link to an actual plan in `.agents/plans/` (e.g. this plan-17 itself, or a more representative one)

The GIF currently shows a fictional `plan-07.md` about Next.js frontend integration. Option A produces the closest match; Option B is the most authentic example of a real devostat plan.

### Task 9: PII / secret scan of plan-13 pivot transcripts [High]

Task 6 wants to mine real Claude Code session transcripts to build a believable "pause → modify → v2" narrative. The best source is the plan-13 v2→v3 pivot (commit `b9e12d1`, 2026-04-10 — "restructure as multi-module Maven project"). Before copying any of that material into the repo (Task 10), scan the candidate transcripts for secrets and PII. Once committed and pushed, leaked tokens are painful to remove from git history.

Candidate transcripts (in `~/.claude/projects/-opt-workspace-code-flow/`):

| Session ID | mtime | Size | Role |
|---|---|---|---|
| `ac2b52af-391e-438d-b4c7-deac3c1c7e03` | 2026-04-10 10:27 | 1.27 MB | Pre-pivot — likely contains the v2→v3 discussion |
| `eb521172-3a09-4ea4-8b10-0b042cd40fab` | 2026-04-10 12:35 | 1.25 MB | First post-pivot execution session |
| `9680eb1a-4435-47f8-9e2d-39ba19d1e132` | 2026-04-10 21:05 | 1.15 MB | Later same-day continuation |

Scans to run on each file:

```bash
# Secret/token shapes
grep -nE 'sk-ant-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|gho_[A-Za-z0-9]{30,}|lin_api_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}' <file>
grep -niE 'api[_-]?key|secret|password|bearer |authorization:|token["'\'': =]' <file>

# Emails and absolute home paths
grep -niE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' <file>
grep -nE '/home/[a-z]+/|/Users/[a-z]+/' <file>

# Env dumps / credential file reads
grep -nE '"env":|HOME=|PATH=|ANTHROPIC_API_KEY|AWS_' <file>
grep -niE '\.env[^a-z]|credentials\.json|\.pem|\.key\b|id_rsa|private.key' <file>
```

Classify each hit as (a) benign, (b) needs redaction, or (c) disqualifies the file. Record a short findings summary (counts per category per file + redaction decisions) as a deviation comment on the task before proceeding. If findings are non-trivial, **stop and raise a major deviation** rather than silently stripping data.

High risk because a single missed secret ends up in git history.

### Task 10: Copy transcripts to docs/session/ [Low]

Create `docs/session/` and copy the three transcripts with filenames preserved verbatim — the UUIDs are stable Claude Code session IDs worth keeping for future cross-reference. Apply any redactions decided in Task 9 during the copy (not in-place on the source files in `~/.claude/`).

### Task 11: Write docs/session/README.md [Low]

Short index (~15-20 lines) explaining:
- Why these files are here (raw material for Task 6's demo cast, and for future demo regens)
- What each transcript is (pre-pivot / post-pivot execution / later)
- The pivot commit hash (`b9e12d1`)
- That these are raw Claude Code session logs — not intended to be diffed, just preserved
- Any redactions applied (carried forward from Task 9 findings)

### Task 12: Preserve pre-pivot demo.cast under docs/demo-1/ [Low]

The README now points to the new plan-13 pivot cast (Task 6). The pre-pivot cast (fictional Next.js todo-app session) from commit `70bea21` is the only surviving demo-authoring work for the "basic plan → execute" narrative, and is worth keeping as reference material for future demo work.

Preserve the source only. The ~1.6MB rendered GIF is not preserved — it can be re-rendered from the cast via the Task 2 `agg` pipeline if ever needed.

Steps:
1. `mkdir -p docs/demo-1 && git show 70bea21:docs/demo.cast > docs/demo-1/demo.cast`
2. Verify the recovered file is a valid asciinema v2 cast (first line is the JSON header `{"version": 2, …}`; subsequent lines are JSON event arrays).
3. Commit as `chore(17): preserve pre-pivot demo.cast under docs/demo-1/`. Push — lefthook will not tag because no `.agents/plans/` file changed.

Folder chosen as `docs/demo-1/` (not `docs/session/…`) to keep demo artefacts grouped under `docs/` while leaving `docs/session/` reserved for raw Claude Code session transcripts.

---

## Risk-sorted order

1. **Task 1** — Edit demo.cast (Medium) ✓
2. **Task 2** — Re-render GIF (Low) ✓
3. **Task 3** — Commit & merge to main (Low) ✓
4. **Task 4** — Reorder README (Low) ✓
5. **Task 9** — PII/secret scan of plan-13 transcripts (High) ✓
6. **Task 10** — Copy transcripts to docs/session/ (Low)
7. **Task 11** — Write docs/session/README.md (Low)
8. **Task 6** — Rewrite demo.cast around plan-13 pivot narrative (Medium)
9. **Task 8** — Link plan + tasks file from README (Low)
10. **Task 12** — Preserve pre-pivot demo.cast under docs/demo-1/ (Low)

Tasks 5 and 7 were subsumed into Task 6 at plan-17-v6 — see Task 6 section. Tasks 10 and 11 are hard prerequisites for Task 6 (which references the preserved transcripts). Task 9 was High-risk and came first regardless (scan complete, 0 secrets). Task 12 has no dependencies on other tasks and may be executed independently.

---

## Verification

1. `docs/demo-small.gif` opens in Firefox, loops and plays correctly, text legible at 1:1 zoom
2. `README.md` renders in Typora — GIF appears proportional to surrounding text
3. `ls -lh docs/demo-small.gif` → target <2MB
4. `git diff main..t/pb-261-readme-demo-gif --name-only` shows only `README.md`, `docs/demo.cast`, `docs/demo-small.gif`
5. After merge, `git log main --oneline -3` shows the merge commit
