# Plan 68 — Project folder restructure

## Context

Implements [docs/proposals/project-restructure.md](../../docs/proposals/project-restructure.md).
Backlog feature: **PB-310** (project restructure / contributor-experience track).

**Goal:** restructure the repo so contributors can orient quickly — `skills/` becomes a
first-class top-level concern, all targets become top-level peers, `core/` is split from `cli/`,
and exploratory work moves under `exp/`. Java package names (`io.bitken.ss.*`) are **not** renamed;
this is a folder/module restructure only.

**Scope decision (this plan):** full restructure — all 16 migration steps from the proposal.
**Move method:** `git mv` so history follows files; **one module per commit**, each commit
independently buildable and reviewable. No big-bang commit; intermediate states must build.

### Current → proposed module map (authoritative — from proposal)

| Current | Proposed |
|---|---|
| `app/…/ss/` (non-cli: `workflow ledger git gw svc conf`) | `core/` |
| `app/…/ss/cli/` + `app/pom.xml` jlink profile | `cli/` |
| `integrations/common/.../jte-src/skills/shared/` | `skills/text/shared/` |
| `integrations/common/.../jte-src/skills/start/SKILL.jte.md` | `skills/text/shared/` |
| `integrations/common/.../jte-src/skills/experimental/` | `skills/text/experimental/` |
| `integrations/common/.../jte-src/skills/start/claude/` | `skills/text/claude/` |
| `integrations/common/.../jte-src/skills/start/gemini/` | `skills/text/gemini/` |
| `integrations/common/src/main/java/` (Target, renderers) | `skills/other/` |
| `integrations/common/scripts/` (TS hooks) | `skills/other/` |
| `integrations/claude/` | `claude/` |
| `integrations/gemini/` | `gemini/` |
| `devel/` | `devtools/` |
| `model/` | `exp/model/` |
| `EXPERIMENTAL.md` | `exp/README.md` |
| `packaging/` | `packaging/` (unchanged; module paths update) |

### Maven dependency reality (verified)

- Root `pom.xml` modules: `integrations/common`, `integrations/claude`, `integrations/gemini`,
  `packaging`, `devel`, `app`.
- `app` is the picocli + jlink module (`io.bitken.ss.*`, all of core+cli today in one tree).
- `integrations/common` (`integration-common`) owns JTE precompilation (`Target` main class,
  antrun rename `.jte.md`→`.jte`, jte-maven-plugin) and the TS `scripts/`.
- `packaging` consumes `integration-common`; `app` and `integrations/common` are siblings.
- Local Maven repo is `/opt/mvn/repository` (per `~/.m2/settings.xml`), not the default.

### What must NOT change (proposal §"What does not change")

- Maven profile names/semantics (`dev`, `prod`, `gemini`, `gemini-dev`, `windows`).
- `build/`, `build-gemini/`, `build-windows/` output dirs (gitignored).
- Java package names `io.bitken.ss.*`.
- `packaging/` orchestration logic (paths update, logic unchanged).
- `.agents/` plan/task files.

---

## Risk analysis & task ordering

Tasks are vertical slices: each is one module move + its pom rewire, committed independently and
green-buildable before the next. Risk-sorted High→Med→Low, **except** dependency-forced ordering:
the `skills/` parent pom (Task 2) and the `app`→`core`/`cli` split sit at the front because every
downstream consumer references them, and a `git mv` that leaves the build red blocks everything.

| # | Task | Risk | Justification |
|---|---|---|---|
| 1 | Split `app/` → `core/` + `cli/`, move jlink to `cli` | **High** | One module with one jlink+shade build splits into two; module-path/jlink wiring is the most fragile thing in the repo. |
| 2 | Create `skills/` parent + split `integrations/common` → `skills/text` (resources) + `skills/other` (Java+TS) | **High** | JTE precompilation (antrun rename, jte-plugin, `Target` main) is tightly coupled to resource paths; classpath of `.jte.md` must survive the move. |
| 3 | Move `integrations/claude/` → `claude/`, `integrations/gemini/` → `gemini/` | **High** | Plugin/extension assembly + hooks; cross-module path rewiring consuming skills output — promoted to High at calibration. |
| 4 | Update `packaging/` module paths to new layout | **High** | Consumes `integration-common`; every input path moves and assembly correctness is release-critical — promoted to High at calibration. |
| 5 | Rename `devel/` → `devtools/`; rewire root pom module list | **Low** | Pure rename + one `<module>` line. |
| 6 | Move `model/` → `exp/model/`; `EXPERIMENTAL.md` → `exp/README.md` | **Low** | No build wiring (TLA+ not compiled); pure move. |
| 7 | Update `DEVELOPMENT.md` + proposal doc paths; final root-pom/relativePath sweep | **Low** | Docs + leftover `<relativePath>` references; no code. |

*Dependency notes:* Task 2 must precede Tasks 3 and 4 (both consume skills output paths).
Task 1 is independent of 2–6 but ordered first by risk. Task 7 is the closing sweep — runs last.

---

## Verification strategy

This plan has no runtime feature to test end-to-end in the usual sense; **the build itself is the
test.** The "integration test" (Phase 2 preamble) is a clean full build from the current layout,
captured as the baseline green state, plus a rendered-output diff harness:

- **Baseline:** `mvn clean install` (or `mvn compile` where sufficient — see memory: `compile` is
  enough for SKILL.md/hooks; `package` only for the jlink image) green on current layout, and a
  snapshot of rendered skill/hook output (`build/`, `build-gemini/`) before any move.
- **Per-task gate:** after each module move, the same build target passes, **and** the rendered
  skill/hook/plugin output is byte-identical to the baseline snapshot (the restructure must not
  change a single rendered artifact). Diffs are the failing-test signal.
- **jlink gate (Task 1):** the jlink image still builds and the CLI binary runs `--version`.

Record the agreed coverage/verification bar with the human at Phase 2 Step 0 (default here is
"identical rendered output + green build per task", since there is no new code to cover).

---

## Tasks

### Task 1: Split app into core and cli with jlink moved to cli [High]
*Depends-on:*
`git mv` the non-cli packages (`workflow ledger git gw svc conf` and shared roots) into a new
`core/` module and the `cli/` package tree into a new `cli/` module. Move the jlink + shade
profile from `app/pom.xml` into `cli/pom.xml`; `cli` depends on `core`. Keep package names
`io.bitken.ss.*` unchanged. Update root pom: replace `app` module with `core` and `cli`.
**De-risk first:** prove `core` compiles standalone and `cli` builds the jlink image and runs
`--version` before hardening pom structure. Verify rendered CLI behavior unchanged.

### Task 2: Restructure skills into text and other with parent pom [High]
*Depends-on: 1*
Create `skills/pom.xml` (parent: registers `text/` as a resource dir, declares `other/` submodule).
`git mv` `integrations/common/src/main/jte-src/skills/{shared,experimental}` and
`start/{SKILL.jte.md,claude,gemini}` into `skills/text/{shared,experimental,claude,gemini}` per the
map. `git mv` `integrations/common/src/main/java/` (Target, SkillRenderer, HooksRenderer,
SessionStartConfigRenderer, etc.) and `integrations/common/scripts/` into `skills/other/`. Rewire
the antrun `.jte.md`→`.jte` rename, the jte-maven-plugin source dirs, and the `Target` main-class
resource paths to the new `text/` location. Update root pom module list. **Rendered skill/hook
output must be byte-identical to baseline.** De-risk by proving the JTE precompile + render pipeline
produces identical output before hardening the parent/submodule pom split.

### Task 3: Move claude and gemini integrations to top level [Med]
*Depends-on: 2*
`git mv` `integrations/claude/` → `claude/` and `integrations/gemini/` → `gemini/`. Update their
poms' `<relativePath>`/parent refs and any path that pointed at `integrations/common` to the new
`skills/` artifact. Update root pom module list. Plugin/extension assembly + hooks output must be
identical to baseline. Note: `integrations/claude/windows/` (plugin-for-windows) is distinct from a
future `desktop/win/` — keep it under `claude/` here, do not conflate.

### Task 4: Repoint packaging to the new layout [Med]
*Depends-on: 2*
Update `packaging/pom.xml` dependency on `integration-common` to the new `skills` artifact and any
input paths that moved (`claude/`, `gemini/`, skills output). Orchestration logic stays unchanged
per proposal — only module coordinates/paths move. Verify the assembled release artifact is
equivalent to baseline.

### Task 5: Rename devel to devtools [Low]
*Depends-on:*
`git mv devel devtools`; update the `<module>devel</module>` line in root pom and any
`<relativePath>` references. Pure rename — verify build still green.

### Task 6: Move model and EXPERIMENTAL into exp [Low]
*Depends-on:*
`git mv model exp/model` and `git mv EXPERIMENTAL.md exp/README.md`. No build wiring (TLA+ is not
compiled or shipped). Grep for any references to `model/` or `EXPERIMENTAL.md` in docs/scripts and
repoint them.

### Task 7: Update docs and final pom relativePath sweep [Low]
*Depends-on: 1,2,3,4,5,6*
Update `DEVELOPMENT.md` with the new paths and build commands (e.g. `mvn compile -pl gemini -am
-Pgemini-dev`). Update the proposal doc's status if appropriate. Final grep sweep across all
`pom.xml` for stale `<relativePath>`/`<module>` references and any lingering `app/` /
`integrations/` paths in scripts or CI. Confirm a clean `mvn clean install` green from the new
layout and rendered output identical to baseline.

---

## Calibration decisions (resolved)

1. **Verification bar:** green build + **byte-identical rendered output** per task vs a
   pre-restructure baseline snapshot. No line-coverage % (this plan introduces no new logic).
2. **Risk overrides:** Tasks 3 and 4 promoted Med → **High** (cross-module path rewiring is
   release-critical). Tasks 1, 2 stay High; Tasks 5–7 stay Low. → De-risk & Harden cycle applies
   to Tasks 1–4; Tasks 5–7 are single-pass.
3. **Future-target placeholders:** `web/`, `desktop/{win,linux,mac}`, `opencode/` are **not**
   created in this plan — no build wiring exists for them. They are added when a real target arrives.
