# Proposal: Thin deterministic recipes out of start-dev SKILL.md into the shipsmooth CLI

## Background

Backlog issue: **PB-310 — Reduce size of SKILL.md.** The generated
`build/skills/start-dev/SKILL.md` is ~25k chars (~6.2k tokens). PB-310's checklist prescribes
the fix proposed here: *"move logic from the Executive layer to the Mechanical layer"* — offload
procedural recipes (git tag/branch incantations, multi-command verifier blocks) from the skill
prose into Java commands the model invokes by name.

The skill's `_partials/workflow/` fragments contain *deterministic* bash recipes spelled out
token-by-token — git tagging sequences, a four-command precondition verifier, a session-resume
inspection block, a branch-creation incantation. Each can be replaced with a single
`shipsmooth <verb>` invocation.

The framing follows `docs/references/code-quality-1.md` Part III §1 (separate what the skill
governs from what the deterministic tool governs), §2/§5 (generator–verifier asymmetry: the tool
is the sound oracle, the model reads a verdict instead of self-certifying), and §10 (volatile
tokens — `plan-07-v1`, branch formats — must live in exactly one place, not be repeated as
high-frequency copy targets in the prompt).

## Decision: print, don't push

Every new command performs the **local** mutation (create tag, create branch) and **prints the
exact `git push` line** to run. The irreversible outward action stays with the human/agent,
mirroring the existing `init` command, which never pushes. No command here calls `git push`.

## Scope boundary

In scope: `[Local]`/git-deterministic recipes in the fragments `git-tagging`, `phase1-plan`
(step 6 verifier), and `phase2-execute` (session-resume pre-flight + Step 0 branch). Out of scope:
the `npm test --coverage` snippets (explicitly per-project, "adjust to your toolchain" —
illustrative, not wrappable), all `[Linear]` MCP bookkeeping prose, and the genuine LLM-judgment
fragments (`control-strategy`, `core-invariants`, calibration steps, de-risk/harden reasoning).

## Proposed commands (all print-don't-push)

| Command | Replaces in skill | Backing |
|---|---|---|
| `shipsmooth plan tag --plan N --kind version\|complete\|abandoned` | entire `git-tagging` fragment | extend `GitTags` (next-version compute already there) |
| `shipsmooth plan preflight --plan N` | `phase1-plan` step 6 four-command verifier | new git gateway reads (clean tree, branch pushed, tag local+remote) |
| `shipsmooth plan branch --issue ID --desc S` | `phase2-execute` Step 0 | new git gateway (create local branch, print push line) |
| `shipsmooth plan resume --plan N` | `phase2-execute` session-resume pre-flight block | compose `plan show` + worktree-list reads |

`permalink` was considered and **dropped**: it serves only `[Linear]` mode (immutable GitHub blob
URL for Linear issue/project descriptions). In `[Local]` mode the helper is never exercised. The
`linear-structure` `rev-list` recipe stays as illustrative `[Linear]`-only prose.

## Behaviour notes

- **`plan preflight` — branch state is WARN, not FAIL.** A dirty tree or missing version tag is a
  hard FAIL (non-zero exit). An unpushed branch / HEAD-ahead-of-upstream is a WARN only — it still
  passes overall, since a committed local plan is sufficient. Prints `PASS` (optionally with
  warnings) or the specific first failure.
- **`plan tag --kind version` refuses to re-tag.** If the computed `plan-N-vK` already exists, the
  command errors with a clear message naming the existing version and exits non-zero — it does
  **not** silently auto-bump. The agent reads the message and decides what to do (e.g. re-run
  after the next commit). No `--version` override flag.

## Approach

Each command is a thin vertical slice: a `Callable<Integer> implements HasSpec` class (mirroring
`plan/Show.java` and `plan/Init.java`), registered under its group in `CommandTree`, backed by a
gateway method, with a test in the `io.bitken.ss.cli` test package. Git-shelling logic extends
`gw/GitTags` (tag) or a new small read-only git-state gateway; commands are hand-built and **not**
Dagger-managed (constructors take gateways, not `@Inject`).

Skill-source edits touch only `_partials/workflow/*.jte.md`. After each fragment is thinned,
`mvn compile` regenerates `build/skills/`. This **intentionally changes** the generated
SKILL.md — verification is that the file shrinks and the new `shipsmooth plan <verb>` references
render correctly.

## Sketch of the work

1. **`plan preflight`** — new read-only git-state gateway + the load-bearing §2/§5 oracle.
2. **`plan tag`** — write-side `GitTags` + next-version compute + refuse-on-exists.
3. **`plan branch`** — reuse the gateway; create `t/{ID}-{slug}`, print push.
4. **`plan resume`** — compose `plan show` + worktree reads.
5. **Thin the fragments** — `git-tagging`, `phase1-plan` step 6, `phase2-execute` resume +
   Step 0; assert generated SKILL.md shrinks; record before/after `wc -c` against PB-310.
