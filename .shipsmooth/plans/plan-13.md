# Plan 13: Local file-based task tracking as alternative to Linear

## Context
The code-flow plugin currently depends on Linear for all task state tracking. This means users can't try the workflow without a Linear account and the Linear MCP server configured. Adding a local file-based alternative makes the plugin accessible to anyone with just Claude Code.

**Linear feature issue:** PB-221

## Design decisions

- **Format: XML** — machine-writable, extensible, not whitespace-sensitive (unlike YAML). Human readability deferred to a future viewer tool.
- **File location: `.agents/plans/plan-{N}-tasks.xml`** — sibling to the plan file. Keeps the flat directory structure, no migration needed for existing plans, compatible with existing git tag conventions.
- **Scripts: TypeScript, pre-compiled to JS** — TS source in `plugin-node/src/main/scripts/tasks/`. SessionStart hook runs `tsc` once into `${CLAUDE_PLUGIN_DATA}/dist/`. Runtime invocation is plain `node`. TS source also copied to `${CLAUDE_PLUGIN_DATA}/src/` for browsing.
- **Dependencies: `typescript` + `fast-xml-parser`** (~26MB) — installed in `${CLAUDE_PLUGIN_DATA}` via SessionStart hook using the documented diff-and-install pattern.
- **Mode detection:** SKILL.md uses `[Linear]` / `[Local]` inline markers at divergence points. Unmarked instructions apply to both modes.
- **Multi-module Maven structure** — repo restructured into three Maven modules: `plugin-node` (TS source + build), `plugin-resources` (SKILL.md, plugin metadata, hooks), `plugin-dist` (assembly). Cleanly separates concerns, eliminates tsconfig.json leak, and gives Node/TS a proper Maven module home.

## Multi-module structure

```
code-flow/                              ← parent POM (packaging=pom)
├── pom.xml
│
├── plugin-node/                        ← Node/TS module
│   ├── pom.xml                         ← runs npm install + tsc via exec-maven-plugin
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── main/
│       │   └── scripts/tasks/
│       │       ├── hello.ts
│       │       ├── init.ts
│       │       └── (future scripts)
│       └── test/
│           ├── fixtures/
│           │   └── (test XML fixtures)
│           └── scripts/tasks/
│               └── init.test.ts
│
├── plugin-resources/                   ← Markdown, JSON metadata, hooks
│   ├── pom.xml                         ← maven-resources-plugin, filtering
│   └── src/main/resources/
│       ├── claude-plugin/
│       │   ├── plugin.json
│       │   └── marketplace.json
│       ├── hooks/
│       │   └── hooks.json
│       └── skills/code-flow/
│           └── SKILL.md
│
└── plugin-dist/                        ← Assembly: merges outputs into build/
    └── pom.xml                         ← depends on plugin-node + plugin-resources
```

### Build flow

- `mvn process-resources` from root: runs all three modules in order
- `plugin-node`: `npm install` (skipped if `node_modules/` is fresh) + `tsc` → `plugin-node/dist/`
- `plugin-resources`: filters and copies SKILL.md, plugin.json, marketplace.json, hooks.json → `plugin-resources/target/`
- `plugin-dist`: assembles final plugin layout into `build/` (dev profile) or `build-prod/` (prod profile)

### SessionStart hook (updated paths)

The hook references `${CLAUDE_PLUGIN_ROOT}/plugin-node/` as source:

```
diff package.json → copy → npm install → cp src/ → tsc → dist/
Source: ${CLAUDE_PLUGIN_ROOT}/plugin-node/package.json
TS src: ${CLAUDE_PLUGIN_ROOT}/plugin-node/src/main/scripts/tasks/
```

## XML schema

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks plan="13" description="Short description" plan-version="plan-13-v1">
  <metadata>
    <backlog-issue>PB-221</backlog-issue>
    <status>active</status> <!-- active | complete | abandoned | in-review -->
    <created>2026-04-09</created>
  </metadata>

  <tasks>
    <task id="1" risk="high" status="pending">
      <name>Task name here</name>
      <commit></commit>
      <created-from>plan-13-v1</created-from>
      <closed-at-version></closed-at-version>
      <comments>
        <comment timestamp="2026-04-09T14:00:00Z">De-risk validated: approach works.</comment>
      </comments>
      <deviations>
        <deviation type="minor" timestamp="2026-04-09T15:00:00Z">Split into two sub-tasks because...</deviation>
      </deviations>
    </task>
  </tasks>

  <project-updates>
    <update timestamp="2026-04-09T13:00:00Z">Plan ready for review.</update>
    <update timestamp="2026-04-09T16:00:00Z" blocked="true">Blocked: fundamental issue with approach X.</update>
  </project-updates>
</plan-tasks>
```

Valid task statuses: `pending`, `in-progress`, `de-risked`, `agent-coded`, `closed`, `needs-triage`, `abandoned`

## Linear operation → XML mapping

| # | Linear operation | XML equivalent |
|---|---|---|
| 1 | Create `[agent]` project | Create `plan-{N}-tasks.xml` with metadata |
| 2 | Create task issues | Add `<task>` elements |
| 3 | Update task status | Update `status` attribute on `<task>` |
| 4 | Post comment for review | Append `<comment>` to task |
| 5 | Record commit reference | Update `<commit>` element |
| 6 | Log minor deviation | Append `<deviation type="minor">` |
| 7 | Flag major deviation | Append `<update blocked="true">` at project level |
| 8 | Close task | Set status to `closed`, record closing version |
| 9 | Mark needs-triage | Set status to `needs-triage` |
| 10 | Complete/archive project | Set metadata status to `complete` |
| 11 | Record audit trail | `<created-from>` and `<closed-at-version>` per task |
| 12 | Post project update | Append `<update>` to `<project-updates>` |

## TypeScript scripts

Located at `plugin-node/src/main/scripts/tasks/`. Each script handles one or more XML operations:

| Script | Operations covered | Example invocation |
|---|---|---|
| `init.ts` | #1, #2 | `node dist/init.js --plan 13 --tasks-from .agents/plans/plan-13.md` |
| `update-status.ts` | #3, #8, #9 | `node dist/update-status.js --plan 13 --task 2 --status agent-coded` |
| `add-comment.ts` | #4 | `node dist/add-comment.js --plan 13 --task 2 --message "De-risk validated"` |
| `set-commit.ts` | #5 | `node dist/set-commit.js --plan 13 --task 2 --commit a1b2c3d` |
| `add-deviation.ts` | #6 | `node dist/add-deviation.js --plan 13 --task 2 --type minor --message "Split task"` |
| `project-update.ts` | #7, #10, #12 | `node dist/project-update.js --plan 13 --message "Ready for review"` |
| `show.ts` | (read-only) | `node dist/show.js --plan 13` (pretty-print current state) |

All scripts read/write `.agents/plans/plan-{N}-tasks.xml` relative to the repo root.

## Tasks (risk-sorted: High → Medium → Low)

### Task 1: SessionStart hook — install deps and compile [High] ✓ DONE
- Completed. Hook in `hooks/hooks.json`, `package.json` + `tsconfig.json` at root, `scripts/tasks/hello.ts`.

### Task 2: Manually verify deps installation [High] ✓ DONE
- Human-verified in a prior session.

### Task 3: Update SKILL.md with `[Linear]`/`[Local]` mode markers [High] ✓ DONE
- Completed at commit 9ebd423.

### Task 4: Restructure repo into multi-module Maven layout [High]
- Create `plugin-node/`, `plugin-resources/`, `plugin-dist/` directories
- Write parent `pom.xml` with `<modules>` listing all three; move profiles and shared properties from old root POM
- Write `plugin-node/pom.xml`: uses `exec-maven-plugin` to run `npm install` (skipped if `node_modules/` is fresh) and `tsc`
- Write `plugin-resources/pom.xml`: uses `maven-resources-plugin` to filter and copy SKILL.md, plugin.json, marketplace.json, hooks.json
- Write `plugin-dist/pom.xml`: assembles final `build/` directory from sibling module outputs
- Move existing source files to new locations:
  - `scripts/tasks/*.ts` → `plugin-node/src/main/scripts/tasks/`
  - `scripts/tasks/*.test.ts` → `plugin-node/src/test/scripts/tasks/`
  - `package.json`, `tsconfig.json` → `plugin-node/`
  - `hooks/hooks.json` → `plugin-resources/src/main/resources/hooks/`
  - `skills/code-flow/SKILL.md` → `plugin-resources/src/main/resources/skills/code-flow/`
  - `src/main/resources/claude-plugin/` → `plugin-resources/src/main/resources/claude-plugin/`
- Update `hooks.json` SessionStart command to reference new paths (`${CLAUDE_PLUGIN_ROOT}/plugin-node/...`)
- Update `tsconfig.json` rootDir/include to match new layout
- Delete old top-level dirs: `scripts/`, `hooks/`, `skills/`, `src/`
- Verify: `mvn process-resources` produces correct `build/` output; `tsconfig.json` no longer appears in `build/`

### Task 5: Implement `init.ts` — create tasks XML from plan file [Medium]
- Draft already exists at commit 7f4ead4 (`scripts/tasks/init.ts` + `init.test.ts`).
- As part of Task 4, these move to `plugin-node/src/main/scripts/tasks/init.ts` and `plugin-node/src/test/scripts/tasks/init.test.ts`.
- Test fixtures (generated XML) go to `plugin-node/src/test/fixtures/`.
- Harden after Task 4 is complete and paths are stable.

### Task 6: Create shared TypeScript types [Low]
- `plugin-node/src/main/scripts/tasks/types.ts`
- Shared interfaces for Task, TaskStatus, RiskLevel, PlanTasks XML schema

### Task 7: Implement `update-status.ts` — change task status [Low]
- Parse XML, find task by id, update status attribute
- Validate status transitions
- Update `<closed-at-version>` when status becomes `closed`

### Task 8: Implement `add-comment.ts` and `add-deviation.ts` [Low]
- Append child elements to a task's comments/deviations sections
- Auto-generate ISO timestamps

### Task 9: Implement `set-commit.ts` and `project-update.ts` [Low]
- `set-commit`: update commit element on a task
- `project-update`: append to project-updates, handle `blocked` flag, update metadata status

### Task 10: Implement `show.ts` — pretty-print task state [Low]
- Read XML and output a human-readable summary (task table + recent updates)

### Task 11: Update README.md [Low]
- Note local task tracking as an alternative to Linear in Features section
- Note multi-module Maven structure in Development section

## Files to create/modify

### New files (multi-module restructure — Task 4)
- `pom.xml` — updated parent with `<modules>` block
- `plugin-node/pom.xml`
- `plugin-node/package.json` (moved from root)
- `plugin-node/tsconfig.json` (moved from root, rootDir/include updated)
- `plugin-node/src/main/scripts/tasks/hello.ts` (moved)
- `plugin-node/src/main/scripts/tasks/init.ts` (moved from `scripts/tasks/`)
- `plugin-node/src/test/scripts/tasks/init.test.ts` (moved from `scripts/tasks/`)
- `plugin-node/src/test/fixtures/` (test XML fixtures)
- `plugin-node/src/main/scripts/tasks/types.ts`, `update-status.ts`, `add-comment.ts`, `add-deviation.ts`, `set-commit.ts`, `project-update.ts`, `show.ts`
- `plugin-resources/pom.xml`
- `plugin-resources/src/main/resources/claude-plugin/plugin.json` (moved)
- `plugin-resources/src/main/resources/claude-plugin/marketplace.json` (moved)
- `plugin-resources/src/main/resources/hooks/hooks.json` (moved, paths updated)
- `plugin-resources/src/main/resources/skills/code-flow/SKILL.md` (moved)
- `plugin-dist/pom.xml`

### Files to delete (after move — Task 4)
- `scripts/` (entire directory)
- `hooks/` (entire directory)
- `skills/` (entire directory)
- `src/` (entire directory)
- Root `package.json`, `tsconfig.json`

## Verification
1. `mvn process-resources -Pdev` → `build/` contains correct layout, no `tsconfig.json` at root of build
2. `mvn process-resources -Pprod` → `build/` uses production plugin name/description
3. `mvn process-resources -pl plugin-node` → compiles only the TS module independently
4. Start new Claude session — SessionStart hook installs deps from new paths, compiles TS
5. `node ${CLAUDE_PLUGIN_DATA}/dist/init.js --plan 99 --tasks-from .agents/plans/plan-12.md` — confirms valid XML
6. Run all `*.test.ts` via `node --test` from `plugin-node/` — all pass
