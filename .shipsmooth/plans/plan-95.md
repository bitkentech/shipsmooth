# plan-95 — Remove the false "Lefthook auto-tags" comment from the Phase 1 skill template

## Context

- Backlog: maintenance — correct misleading workflow documentation (local backlog; no external issue).
- Supersedes nothing; standalone doc-correctness fix.
- Lineage: commit `b3715ae` (2026-06-03, "docs(skills): remove lefthook section from
  git-tagging and start-tla") deliberately removed the optional `lefthook.yml` automation
  snippet — agents follow manual tagging directly. This fix completes that removal by
  deleting the last stale reference left behind. There was never a real `lefthook.yml` file
  in the repo; it only ever existed as a documented optional snippet.

## Problem

The kickoff was "left hook errors explore and fix". Investigation found there is **no
lefthook in this project at all** — no `lefthook.yml`, no `core.hooksPath`, only the
default `.git/hooks/*.sample` files.

Despite that, the Phase 1 skill template carries a stale comment that claims a lefthook
will auto-create and push the plan version tag:

`skills/shared/workflow/phase1-plan.jte.md:24`
```bash
git push origin t/{issue-id}-{short-description}
# Lefthook auto-tags plan-07-v1 and pushes it
```

This directly contradicts the canonical tagging instructions in
`skills/shared/workflow/git-tagging.jte.md`, which describe **manual** tagging:

```bash
${model.cliBin()} plan tag --plan {N} --kind version
# prints: git push origin plan-{N}-v{K}  — run that line to push
```

### Why this is a bug

The false comment tells the agent that pushing the plan commit auto-creates and pushes
the `plan-{N}-v1` tag. Because no such hook exists, an agent that trusts the comment will
**skip** the manual `plan tag` step and the version tag will never be created — breaking
the immutable audit trail that the entire start workflow depends on (Core Invariant #5:
tags are permanent; Audit Trail section relies on `plan-{N}-vK` tags existing).

### Scope

The stale phrase lives in exactly one source-of-truth file:
`skills/shared/workflow/phase1-plan.jte.md`. A full repo sweep (case-insensitive
`lefthook`/`auto-tag`/`pre-push`) confirms every other hit is non-source:

- build-generated artifacts that regenerate from the source on the next build
  (`skills/pkg/build/jte-src/.../phase1-plan.jte`, precompiled `Jtephase1planGenerated.java`);
- immutable historical records left untouched — past plan files (`plan-17`, `plan-65`),
  captured session logs (`docs/session/*.jsonl`), and the demo recording
  (`docs/demo.cast`, deliberately left as-is).

## Design decision

Replace the false hook comment with an explicit pointer to the manual tagging step, so the
template is self-consistent with `git-tagging.jte.md`. Keep the change minimal and inside
the existing fenced commit block. Do not introduce or document a lefthook — none is wanted;
manual `plan tag` is the intended workflow.

## Verification

- `grep -rn -i "lefthook\|auto-tag" skills/shared/` returns no hits in source templates.
- A build regenerates the JTE artifacts so they no longer contain the stale phrase
  (`./gradlew :skills:pkg:compileJte` or the project's skill build), confirming the source
  is the only thing that needed changing.

## Tasks

### Task 1: Replace the false Lefthook comment with the manual tagging step [Low]

Edit `skills/shared/workflow/phase1-plan.jte.md`. In the Phase 1 step-5 commit block,
remove the line `# Lefthook auto-tags plan-07-v1 and pushes it` and replace it with a
pointer to the manual `plan tag --kind version` step (consistent with
`git-tagging.jte.md`). Confirm no other source template under `skills/shared/` references
a lefthook or auto-tagging.
