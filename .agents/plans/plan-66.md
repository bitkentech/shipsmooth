# Plan 66 — thin deterministic recipes out of start-dev SKILL.md into the shipsmooth CLI

## Context

Backlog issue: **PB-310 — Reduce size of SKILL.md.** The generated
`build/skills/start-dev/SKILL.md` is ~25k chars (~6.2k tokens). PB-310's own checklist
prescribes the fix used here: *"move logic from the Executive layer to the Mechanical
layer"* — offload procedural recipes (git tag/branch/rev-list incantations, multi-command
verifier blocks) from the skill prose into Java commands the model invokes by name.

This is the natural successor to **plan-65**, which split the monolithic `base-workflow.jte`
into per-section fragments under `_partials/workflow/`. Those fragments are now individually
thinnable. This plan targets the *deterministic* ones — the bash recipes the skill currently
spells out token-by-token — and replaces each with a single `shipsmooth <verb>` invocation.

The framing follows `docs/references/code-quality-1.md` Part III §1 (separate what the skill
governs from what the deterministic tool governs), §2/§5 (generator–verifier asymmetry: the
tool is the sound oracle, the model reads a verdict instead of self-certifying), and §10
(volatile tokens — `plan-07-v1`, branch/URL formats — must live in exactly one place, not be
repeated as high-frequency copy targets in the prompt).

### Decision: print, don't push

Every new command performs the **local** mutation (create tag, create branch, compute URL)
and **prints the exact `git push` line** to run. The irreversible outward action stays with
the human/agent, mirroring the existing `init` command, which never pushes. No command in this
plan calls `git push`.

### Scope boundary

In scope: `[Local]`/git-deterministic recipes in the fragments `git-tagging`, `phase1-plan`
(step 6 verifier), `phase2-execute` (session-resume pre-flight + Step 0 branch), and
`linear-structure` (permalink/rev-list). Out of scope: the `npm test --coverage` snippets
(explicitly per-project, "adjust to your toolchain" — illustrative, not wrappable), all
`[Linear]` MCP bookkeeping prose, and the genuine LLM-judgment fragments (`control-strategy`,
`core-invariants`, calibration steps, de-risk/harden reasoning).

## Target commands (all print-don't-push)

| Command | Replaces in skill | Backing |
|---|---|---|
| `shipsmooth tag --plan N --kind version\|complete\|abandoned` | entire `git-tagging` fragment | extend `GitTags` (next-version compute already there) |
| `shipsmooth preflight --plan N` | `phase1-plan` step 6 four-command verifier | new git gateway reads (clean tree, branch pushed, tag local+remote) |
| `shipsmooth branch --issue ID --desc S` | `phase2-execute` Step 0 | new git gateway (create local branch, print push line) |
| `shipsmooth resume --plan N` | `phase2-execute` session-resume pre-flight block | compose `show` + worktree-list reads |

`permalink` was considered and **dropped**: it serves only `[Linear]` mode (immutable GitHub
blob URL for Linear issue/project descriptions). This repo runs the workflow in `[Local]` mode,
so the helper would never be exercised here. The `linear-structure` `rev-list` recipe stays as
illustrative prose in Task 5.

## Approach

Each command is a thin vertical slice: a `Callable<Integer> implements HasSpec` class (mirroring
`plan/Show.java` and `plan/Init.java`), registered in `CommandTree.buildCommands`, backed by a
gateway method, with a unit/integration test in the `io.bitken.ss.cli` test package. Git-shelling
logic extends `gw/GitTags` (tag/rev-list) or a new small read-only git-state gateway; commands
are hand-built in `CommandTree` and are **not** Dagger-managed (their constructors take gateways,
not `@Inject`).

Skill-source edits touch only `_partials/workflow/*.jte.md`. After each fragment is thinned,
`mvn compile` regenerates `build/skills/`. Unlike plan-65 (which required byte-identical output),
this plan **intentionally changes** the generated SKILL.md — the verification is that the
generated file shrinks and the new `shipsmooth <verb>` references render correctly, not that it
is unchanged.

## Risk-sorted tasks

### Task 1: preflight command — precondition verifier as a sound gate [High]
*Depends-on:*

Highest risk: it introduces a new read-only git-state gateway and is the load-bearing §2/§5
oracle. Add `shipsmooth preflight --plan N` that checks (a) working tree clean, (b) current
branch has an upstream and HEAD is not ahead of it, (c) `plan-N-v*` tag exists locally and on
remote. A dirty tree or missing version tag is a hard **FAIL** (non-zero exit). An unpushed
branch / HEAD-ahead-of-upstream is a **WARN** only — it still passes overall, since a committed
local plan is sufficient. Prints `PASS` (optionally with warnings) or the specific first failure.
New gateway method + command class + registration + integration test covering pass, pass-with-
warning, and each FAIL mode. No skill edit yet (proves the oracle before prose depends on it).

### Task 2: tag command — version/complete/abandoned, print-don't-push [Medium]
*Depends-on:*

Add `shipsmooth tag --plan N --kind version|complete|abandoned`. For `version`, compute the next
`plan-N-vK` from existing tags (reuse `GitTags.getPlanVersion` read). If that tag already exists,
**refuse**: print a clear message naming the existing version and exit non-zero — do not
auto-bump. Otherwise create it locally and print the `git push origin <tag>` line.
`complete`/`abandoned` create the fixed-name tag on HEAD and print the push line. Command class +
`GitTags` write method + registration + test covering: version created, version-already-exists
refusal, and complete/abandoned. No push performed.

### Task 3: branch command — task branch create + print push [Low]
*Depends-on: 1*

Add `shipsmooth branch --issue ID --desc S` that creates local branch `t/{ID}-{S}` (slugified
desc) and prints `git push -u origin t/{ID}-{S}`. Reuses the read-only git gateway from Task 1.
Command + gateway method + test asserting branch created and push line printed. No push.

### Task 4: resume command — session-resume state summary [Low]
*Depends-on: 1,3*

Add `shipsmooth resume --plan N` composing existing reads: XML presence (or the `init` hint when
absent), the `show` summary, `git worktree list`, and a flag for any stale `integration/plan-N`
worktree — printing a single resume report ending in the next actionable task. Composes the
Task 1 gateway and `TaskStore`; no new git primitive. Command + test.

### Task 5: thin the fragments to invoke the new commands [Low]
*Depends-on: 1,2,3,4*

Edit only after all commands exist and pass. Rewrite `_partials/workflow/git-tagging.jte.md`
(→ `shipsmooth tag`), `phase1-plan.jte.md` step 6 (→ `shipsmooth preflight`), and
`phase2-execute.jte.md` session-resume block (→ `shipsmooth resume`) and Step 0
(→ `shipsmooth branch`). The `linear-structure.jte.md` `rev-list`/permalink block stays as
illustrative `[Linear]`-only prose (no command to point at). Keep all `${...}` cliBin
interpolation and the Phase-2 nested per-agent includes verbatim. `mvn compile`; assert the
generated `build/skills/start-dev/SKILL.md` shrinks and the new invocations render. Record
before/after `wc -c` in the commit message against PB-310.

## Verification

- Per command: integration test in `io.bitken.ss.cli` exercising the success path and (for
  `preflight`/`tag`) each FAIL/refusal mode. `mvn test` green.
- §6 note: commands never push — tests assert the printed push line, never that a remote moved.
- Final: `mvn compile` regenerates skills; `wc -c build/skills/start-dev/SKILL.md` is smaller
  than the pre-plan baseline; grep confirms the removed bash recipes are gone and the
  `shipsmooth <verb>` references are present. Snapshot the baseline to `.agents/tmp/` before
  Task 5 for the diff.

## Resolved decisions (calibration)

- **`preflight` branch state → WARN, not FAIL.** A committed-but-unpushed branch (or HEAD ahead
  of upstream) prints a warning and still passes overall. Only a dirty tree or a missing version
  tag is a hard FAIL. As long as the plan commit exists locally, preflight passes.
- **`tag --kind version` refuses to re-tag.** If the computed `plan-N-vK` already exists, the
  command errors with a clear message naming the existing version and exits non-zero — it does
  **not** silently auto-bump. The agent reads the message and decides what to do (e.g. re-run
  after the next commit). No `--version` override flag is added.
- **`permalink` dropped** — see Target commands above ([Linear]-only; repo is [Local]).
