# Plan 15 — Rename plugin & skill from "code-flow" to "devostat"

## Narrative

The plugin is being renamed to **devostat** — a thermostat analogy reflecting the control-theory foundation of the workflow (risk-quality feedback loop with gain parameters $K_S$, $K_Q$ and sampling interval $T_s$). Tagline: *"Agentic energy, channelled with skill"*.

GitHub URLs will be updated to `pramodbiligiri/devostat` in anticipation of a repo rename (tracked as a separate backlog issue for the human to execute).

Historical plan files in `.agents/plans/` are left unchanged — they reflect the project name at the time they were written.

## Design decisions

- **Dev profile** renames to `devostat-dev` (paralleling the current `code-flow-dev` convention)
- **Skill directory** `skills/code-flow/` → `skills/devostat/` (source); build output follows automatically via Maven property
- **README** gets the new tagline prominently below the title
- **No functional changes** — this is a pure rename + tagline addition

## Tasks (risk-sorted: High → Low)

### Task 1 — Maven & directory rename (Risk: Medium)
Rename across all 4 pom.xml files (root + 3 children), rename the skill source directory, and verify the build still works.

**Files:**
- `pom.xml` — artifactId, dev/prod profile properties
- `plugin-resources/pom.xml` — parent artifactId, skill source directory path
- `plugin-node/pom.xml` — parent artifactId
- `plugin-dist/pom.xml` — parent artifactId
- `plugin-resources/src/main/resources/skills/code-flow/` → `skills/devostat/`

**Verification:** `mvn process-resources` succeeds, `build/skills/devostat-dev/SKILL.md` exists.

### Task 2 — Plugin metadata & NPM package (Risk: Low)
Update all JSON config files with the new name and GitHub URLs.

**Files:**
- `.claude-plugin/plugin.json`
- `plugin-resources/src/main/resources/claude-plugin/plugin.json`
- `plugin-resources/src/main/resources/claude-plugin/marketplace.json`
- `plugin-node/package.json`
- `plugin-node/package-lock.json`

### Task 3 — Task script (Risk: Low)
Update console message in hello.ts.

**Files:**
- `plugin-node/src/main/scripts/tasks/hello.ts`

### Task 4 — Manual verification checkpoint
Human verifies the rename works end-to-end (build, dev plugin load, skill invocation) before proceeding to docs.

**Wait for explicit go-ahead.**

### Task 5 — README overhaul (Risk: Low)
Rename title, add tagline, update install/usage commands and links.

**Files:**
- `README.md`

### Task 6 — DEVELOPMENT.md update (Risk: Low)
Replace all code-flow references with devostat.

**Files:**
- `DEVELOPMENT.md`

### Task 7 — Demo cast update (Risk: Low)
Update code-flow references in the asciinema recording file.

**Files:**
- `docs/demo.cast`
