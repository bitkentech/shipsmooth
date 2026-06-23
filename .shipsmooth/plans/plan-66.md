# Plan 66 — regroup the shipsmooth CLI into noun subcommands

## Context

Backlog issue: **PB-310 — Reduce size of SKILL.md.**

The `shipsmooth` CLI has grown a flat tree of ~19 top-level commands (`init`, `show`,
`add-task`, `update-status`, `set-commit`, `project-update`, `worker-base`, `worker-init`, …).
The Java packages already cluster them by noun (`cli/plan/`, `cli/task/`, `cli/worker/`,
`cli/ledger/`), and `ledger` is *already* exposed as a group (`ledger list`, `ledger verify`,
`ledger read`). The rest is flat. This plan brings the command surface in line with the package
structure: noun groups with verb subcommands (`shipsmooth plan init`, `shipsmooth task add`),
the conventional CLI shape (`git remote add`, `kubectl get pods`).

This is **preparatory work** for `docs/proposals/thin-skill-recipes-into-cli.md`, which adds
new `plan tag|preflight|branch|resume` commands and thins the skill's deterministic recipes.
Those commands should be born in a grouped tree rather than added flat and moved later; this
plan establishes that tree first. Reducing the number of distinct top-level command names the
skill must teach also directly serves PB-310.

## Decision: clean break, no aliases

Old flat names are **removed**, not aliased. The only callers are the skill fragments under
`_partials/workflow/`, the gemini `.toml` command files, and the CLI tests — all updated in
this plan. A clean tree is worth more than transitional compatibility for an internal CLI.

## Target command tree

```
shipsmooth
├── plan
│   ├── init        ← init            (cli/plan/Init)
│   ├── show        ← show            (cli/plan/Show)      keyed by --plan; the plan's task view
│   └── update      ← project-update  (cli/plan/ProjectUpdate)
├── task
│   ├── add         ← add-task        (cli/task/AddTask)
│   ├── comment     ← add-comment     (cli/task/AddComment)
│   ├── deviation   ← add-deviation   (cli/task/AddDeviation)
│   ├── status      ← update-status   (cli/task/UpdateStatus)
│   └── set-commit  ← set-commit      (cli/task/SetCommit)
├── ledger                            (unchanged — already a group)
│   ├── list · verify · read
│   ├── record-commit            ← ledger-record-commit
│   ├── record-patch-integrated  ← ledger-record-patch-integrated
│   ├── resolver-complete        ← ledger-resolver-complete
│   └── watch                    ← ledger-watch
├── worker                            (experimental)
│   ├── base        ← worker-base
│   ├── init        ← worker-init
│   ├── finish      ← worker-finish
│   └── cleanup     ← worker-cleanup
├── claim                             (top-level verb — spans the parallel flow, not CRUD)
└── integrate                         (top-level verb)
```

### Naming rationale (the non-obvious choices)

- `show → plan show`: keyed by `--plan`; it is the plan's task view (not `task list`, which
  would imply a per-task selector that does not exist here).
- `project-update → plan update`: "project" is Linear vocabulary; in `[Local]` it is the plan.
- `claim` / `integrate` stay top-level: they are verbs spanning the parallel-execution flow,
  not CRUD on a single noun. `ledger`/`worker` group cleanly; these do not.
- `ledger-*` / `worker-*` drop the hyphen prefix and become true subcommands under their group.

## Approach

`cli/ledger/Ledger.java` is the existing template for a noun group: a parent
`Callable<Integer> implements HasSpec` whose constructor registers nested `HasSpec` subcommands
via `spec.addSubcommand(...)`, with a `call()` that prints group usage. Replicate that for `plan`,
`task`, and `worker` parents.

Each leaf command's `spec.name(...)` shortens to its verb (e.g. `Init` → `"init"` under `plan`,
`UpdateStatus` → `"status"` under `task`). `CommandTree.buildCommands` stops registering leaves
flat and instead registers the group parents (each parent constructed with the gateways its
leaves need). Commands remain hand-built and **not** Dagger-managed. The experimental gating in
`CommandTree` (worker/integrate) is preserved at the group or leaf level as appropriate.

## Risk-sorted tasks

### Task 1: introduce group parents and re-nest existing leaves [High]
*Depends-on:*

Highest risk: it restructures `CommandTree` registration and every leaf's `spec.name`. Create
`Plan`, `Task`, and `Worker` group-parent classes modelled on `Ledger`. Shorten each leaf's
`spec.name` to its verb per the target tree. Rewire `CommandTree.buildCommands` to register the
group parents (passing through the gateways each leaf needs) instead of flat leaves; keep
`claim`/`integrate` top-level and preserve experimental gating. Update `CommandsTest` and any
other `io.bitken.ss.cli` tests that assert argv strings. `mvn test` green; `shipsmooth --help`
shows the grouped tree; `shipsmooth plan --help` / `task --help` / `worker --help` list leaves.

### Task 2: fold ledger-* and worker-* prefixes into their groups [Medium]
*Depends-on: 1*

The `ledger-record-commit`, `ledger-record-patch-integrated`, `ledger-resolver-complete`, and
`ledger-watch` leaves currently register flat alongside the `ledger` group; move them **inside**
it as `ledger record-commit` etc. Likewise ensure `worker-*` are `worker base|init|finish|cleanup`
under the Worker parent from Task 1. Update tests. Verify `ledger --help` and `worker --help`
enumerate all subcommands and the old flat forms no longer resolve.

### Task 3: update skill fragments to grouped command names [Low]
*Depends-on: 1,2*

In `_partials/workflow/*.jte.md`, rewrite every `${cliBin()} <flat>` to its grouped form:
`init→plan init`, `show→plan show`, `project-update→plan update`, `add-comment→task comment`,
`add-deviation→task deviation`, `update-status→task status`, `set-commit→task set-commit`
(`ledger …` already grouped — verify only). Keep all `${...}` interpolation and the Phase-2
nested per-agent includes verbatim. `mvn compile`; grep the generated
`build/skills/start-dev/SKILL.md` to confirm no flat name survives and grouped forms render.

### Task 4: update gemini .toml callers [Low]
*Depends-on: 1,2*

Update `integrations/gemini/.../commands/start.toml` and `start-dev.toml` for any embedded CLI
invocations using the renamed commands. If they reference commands only via the shared skill body
(not literal CLI lines), confirm there is nothing to change and record that. Build the gemini
integration to confirm.

## Verification

- `mvn test` green; `CommandsTest` updated to the grouped argv.
- `shipsmooth --help` lists `plan`, `task`, `ledger`, `worker`, `claim`, `integrate` and no flat
  leaf names; each group's `--help` lists its verbs.
- No old flat name resolves (e.g. `shipsmooth show` errors; `shipsmooth plan show` works).
- `mvn compile` regenerates skills; grep confirms generated SKILL.md uses only grouped forms.
- Snapshot the generated SKILL.md to `.agents/tmp/` before Task 3 for a before/after diff.

## Resolved decisions (calibration)

- **Clean break, no aliases** — old flat command names are removed, not kept as aliases.
- **`claim` and `integrate` stay top-level** — they are cross-cutting verbs, not noun CRUD.
- **`show → plan show`, `project-update → plan update`** — chosen over `task list` / keeping
  "project" vocabulary, per the naming rationale above.
