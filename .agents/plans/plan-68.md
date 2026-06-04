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
| `integrations/common/src/main/java/` (Target, renderers) | `skills/pkg/` |
| `integrations/common/scripts/` (TS hooks) | `skills/pkg/` |
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
| 2 | Create `skills/` parent + split `integrations/common` → `skills/text` (resources) + `skills/pkg` (Java+TS) | **High** | JTE precompilation (antrun rename, jte-plugin, `Target` main) is tightly coupled to resource paths; classpath of `.jte.md` must survive the move. |
| 3 | Move `integrations/claude/` → `claude/`, `integrations/gemini/` → `gemini/` | **High** | Plugin/extension assembly + hooks; cross-module path rewiring consuming skills output — promoted to High at calibration. |
| 4 | Update `packaging/` module paths to new layout | **High** | Consumes `integration-common`; every input path moves and assembly correctness is release-critical — promoted to High at calibration. |
| 5 | Rename `devel/` → `devtools/`; rewire root pom module list | **Low** | Pure rename + one `<module>` line. |
| 6 | Move `model/` → `exp/model/`; `EXPERIMENTAL.md` → `exp/README.md` | **Low** | No build wiring (TLA+ not compiled); pure move. |
| 7 | Update `DEVELOPMENT.md` + proposal doc paths; final root-pom/relativePath sweep | **Low** | Docs + leftover `<relativePath>` references; no code. |

*Dependency notes:* Task 2 must precede Tasks 3 and 4 (both consume skills output paths).
Task 1 is independent of 2–6 but ordered first by risk. Task 7 is the closing sweep — runs last.

---

## Verification strategy

This plan introduces no new runtime feature; **the build is the test.** The restructure must
produce byte-identical rendered output (skills, hooks, scripts, plugin metadata) and a working
jlink image — only the source/module layout changes.

### Baseline (captured at Phase 2 start — done)

`mvn clean compile` on the pre-restructure layout renders the full output into `build/`. That
output was copied to **`.agents/tmp/expected-output/`** as the expected result. It contains:

- `skills/*/SKILL.md` — the 4 rendered skills (start-dev + 3 experimental)
- `hooks/hooks.json` — rendered hooks
- `dist/session-start.js`, `dist/adm-zip-bundle.js`, `dist/session-start-config.json` — scripts
- `.claude-plugin/plugin.json`, `.claude-plugin/marketplace.json`, `package.json` — plugin metadata

Note: `mvn compile` alone leaves `packaging`'s `verify-jlink-image-exists` check failing because
no jlink image exists yet — that check is satisfied separately by the jlink build (see final gate).

### Per-task gate (each module move)

1. **Build green:** `mvn clean compile` succeeds for the moved/rewired modules (the JTE render +
   TS bundle pipeline runs without error). For the closing reactor, the `packaging` jlink-verify
   is satisfied by building the image first (`mvn -pl cli -am -Pjlink package`, path updates per
   Task 1).
2. **Each task commits a green, independently buildable state** (one module per commit). If a move
   leaves the build red, it is fixed before committing — no red commits.

### Final gate (after Task 7)

1. **Identical rendered output:** `diff -r build/ .agents/tmp/expected-output/` returns no
   differences — the restructure changed not one rendered artifact.
2. **jlink image builds and runs:** `mvn -pl cli -am -Pjlink package` produces the jlink image and
   the CLI binary runs `--version`.
3. **Clean full build:** `mvn clean install` green from the new layout.

No line-coverage threshold applies (agreed at Phase 2 Step 0) — this plan adds no new logic; the
identical-output + working-jlink gates are the bar.

---

## Tasks

### Task 1: Split app into core and cli with jlink moved to cli [High]
*Depends-on:*
`git mv` the non-cli packages (`workflow ledger git gw svc conf` and shared roots) into a new
`core/` module and the `cli/` package tree into a new `cli/` module. Move the jlink + shade
profile from `app/pom.xml` into `cli/pom.xml`; `cli` depends on `core`. Keep package names
`io.bitken.ss.*` unchanged. Update root pom: replace `app` module with `core` and `cli`.

**JPMS decision (resolved):** `core` and `cli` become **two JPMS modules**, not one merged module.
The single `app/src/main/java/module-info.java` (module `io.bitken.ss`) splits into:
- `core` → module `io.bitken.ss.core`: `exports` every package `cli` consumes
  (`workflow ledger gw svc git conf` + the bare `io.bitken.ss` package holding `Build`), and keeps
  the JAXB/Jackson `opens`/`requires` (`io.bitken.ss.jaxb`→jakarta.xml.bind,
  `.ledger`/`.workflow.integration`→jackson).
- `cli` → module `io.bitken.ss.cli`: `requires io.bitken.ss.core`, keeps the picocli
  `requires`/`opens` (`io.bitken.ss.cli.*`→info.picocli).

Rationale: a compiler-enforced boundary makes the "core has no target knowledge" invariant
mechanical, and gives future `web`/`desktop` targets a clean standalone `core` to `requires`. The
recurring cost — adding one `exports` line when a new core package is consumed across the boundary —
is treated as a feature (it forces an explicit surface decision), not a tax. Known sharp edges to
get right in De-risk: module-name vs package-name overlap for `io.bitken.ss.cli`; the dagger shade
(automatic module, jlink-incompatible) must live with the right module; and the
`META-INF/native-image/io.bitken.ss/` config dir is keyed to the old module name and must be
repartitioned/renamed for the two-module graph. `Build.java` (generated, package `io.bitken.ss`,
referenced only by `cli/CommandTree`) goes to `core` and is exported.

**jlink / build realities (from `app/pom.xml` analysis — the fragile surface):**
- The `jlink` profile's `module.path` lists every dependency JAR explicitly plus the project JAR.
  After the split it must also include the `core` JAR; `--add-modules io.bitken.ss` becomes
  `io.bitken.ss.cli` (which transitively resolves `io.bitken.ss.core` via `requires`). All ~6
  cross-platform jlink executions, the SCC launcher, and the `--launcher` mapping reference
  `io.bitken.ss/io.bitken.ss.cli.Shipsmooth` and each must update to the new module name.
- **Dagger is shaded into the JAR** and `module-info.class` is re-injected post-shade (Shade strips
  it). Dagger DI is centralized in `conf` (`@Inject`/`@Module`/`@Component` only appear there;
  CLI commands are hand-built in `CommandTree`, not Dagger-managed). So the Dagger shade belongs
  with **`core`** (where the component graph lives).
- **jaxb2 plugin** generates `io.bitken.ss.jaxb` from `src/main/resources/plan-tasks.xsd`. That
  package is consumed by both core (`workflow`, `gw`, `svc`) and cli (`cli/plan`, `cli/worker`,
  `cli/task`) → the XSD + jaxb generation live in **`core`** and `io.bitken.ss.jaxb` is **exported**.
- `XmlServiceAgentRunner` (exec-plugin `mainClass`, native-image agent) has **no source file** —
  pre-existing dead config; the native-image plugin is commented out / skipped. Not fixed here.
- `plan-tasks.xsd` and `META-INF/native-image/io.bitken.ss/` resources move to **`core`** and
  **`cli`** respectively (native-image config is keyed to the executable/module name).

**De-risk first:** prove `core` compiles standalone and `cli` builds the jlink image and runs
`--version` before hardening pom structure. Verify rendered CLI behavior unchanged.
**Verify:** `mvn -pl core -am compile` green; `mvn -pl cli -am -Pjlink package` builds the jlink
image and the resulting binary runs `--version`; all `app` tests pass under the new modules.

### Task 2: Restructure skills into text and pkg with parent pom [High]
*Depends-on: 1*
Split `integrations/common` into `skills/text/` (JTE content) + `skills/pkg/` (renderers + TS
scripts) under a `skills/pom.xml` parent.

**Target layout — one folder per skill under `text/` (decided with human):**
```
skills/
  pom.xml            ← parent: text/ = JTE resource root, declares pkg/ submodule
  text/
    start/SKILL.jte.md             ← the start skill, in its own folder
    refine/SKILL.jte.md + rules/   ← experimental skills become peer skill folders
    start-parallel/SKILL.jte.md
    start-tla/SKILL.jte.md + tla-model.jte.md
    shared/                        ← partials + target snippets shared across skills
      base-workflow.jte.md  parallel-execution.jte.md  workflow/ (12)
      claude/ (13)                 ← Claude target snippets the SHARED workflow selects
      gemini/ (13)                 ← Gemini target snippets the SHARED workflow selects
  pkg/
    pom.xml          ← renderer module (was integration-common); Target, *Renderer, scripts/
```
Per-skill target overrides (`<skill>/claude/`, `<skill>/gemini/`) are an *available* convention for
future skill-specific snippets; today all claude/gemini snippets are shared (imported by
`shared/workflow/phase2-execute.jte.md` and `shared/parallel-execution.jte.md` via
`@if(model.isGemini())…@else…`), so they live in `shared/claude` + `shared/gemini`.

**JTE naming insight (de-risked):** template class names derive from the path under the JTE root,
but they are *not* output — only the rendered `SKILL.md`/`hooks.json` is. `SkillRenderer.renderSkill`
takes a template path and writes to `skills/<model.skillName()>/SKILL.md`; **path and output skill
name are decoupled**. So renaming template paths is safe as long as the 4 path strings in
`SkillRenderer` and every `@template.*` include are updated consistently; the byte-diff gate proves
output unchanged. Reference rewiring required:
- `SkillRenderer.java` (4 paths): `skills/start/SKILL.jte`→`start/SKILL.jte`;
  `skills/experimental/refine/SKILL.jte`→`refine/SKILL.jte`; same for `start-tla`, `start-parallel`.
- `@template.*` includes (~30): drop the `skills.` segment and remap `start.claude`→`shared.claude`,
  `start.gemini`→`shared.gemini` (e.g. `@template.skills.start.gemini.set-commit-hardening` →
  `@template.shared.gemini.set-commit-hardening`); `skills.shared.workflow.X` → `shared.workflow.X`.
- jte-plugin/antrun JTE source root becomes `text/`; new template names are relative to it.

`git mv` content into the per-skill folders; `git mv` `src/main/java/` + `scripts/` into `skills/pkg/`.
Update root pom module list (`integrations/common` → `skills` parent + `skills/pkg`). Note: the JTE
templates reference `io.bitken.ss.jaxb`? No — they use `PluginModel`; the renderer module depends on
no core types, so it is independent of Task 1's core/cli split (the `Depends-on: 1` is reactor-order
only). De-risk by proving the JTE precompile + render pipeline produces byte-identical output before
hardening the parent/submodule pom split.
**Verify:** `mvn -pl skills/pkg -am compile` renders without error; `diff -r build/skills
.agents/tmp/expected-output/skills`, `diff build/hooks/hooks.json
.agents/tmp/expected-output/hooks/hooks.json`, and `diff -r build/dist
.agents/tmp/expected-output/dist` all show no differences.

### Task 3: Move claude and gemini integrations to top level [High]
*Depends-on: 2*
`git mv` `integrations/claude/` → `claude/` and `integrations/gemini/` → `gemini/`. Update their
poms' `<relativePath>`/parent refs and any path that pointed at `integrations/common` to the new
`skills/` artifact. Update root pom module list. Plugin/extension assembly + hooks output must be
identical to baseline. Note: `integrations/claude/windows/` (plugin-for-windows) is distinct from a
future `desktop/win/` — keep it under `claude/` here, do not conflate.
**Verify:** `mvn -pl claude -am compile` and `mvn -pl gemini -am compile` green; rendered plugin
metadata (`.claude-plugin/*.json`, `package.json`) and hooks still diff-clean vs
`.agents/tmp/expected-output/`.

### Task 4: Repoint packaging to the new layout [High]
*Depends-on: 2*
Update `packaging/pom.xml` dependency on `integration-common` to the new `skills` artifact and any
input paths that moved (`claude/`, `gemini/`, skills output). Orchestration logic stays unchanged
per proposal — only module coordinates/paths move. Verify the assembled release artifact is
equivalent to baseline.
**Verify:** `mvn -pl packaging -am package` succeeds (jlink-verify check passes against the
Task 1 image) and `diff -r build/ .agents/tmp/expected-output/` is clean.

### Task 5: Rename devel to devtools [Low]
*Depends-on:*
`git mv devel devtools`; update the `<module>devel</module>` line in root pom and any
`<relativePath>` references. Pure rename — verify build still green.
**Verify:** `mvn -pl devtools -am compile` green; root reactor still resolves all modules.

### Task 6: Move model and EXPERIMENTAL into exp [Low]
*Depends-on:*
`git mv model exp/model` and `git mv EXPERIMENTAL.md exp/README.md`. No build wiring (TLA+ is not
compiled or shipped). Grep for any references to `model/` or `EXPERIMENTAL.md` in docs/scripts and
repoint them.
**Verify:** `grep -rn 'model/\|EXPERIMENTAL.md'` outside `exp/` returns no stale references;
build unaffected (no build wiring touched).

### Task 7: Update docs and final pom relativePath sweep [Low]
*Depends-on: 1,2,3,4,5,6*
Update `DEVELOPMENT.md` with the new paths and build commands (e.g. `mvn compile -pl gemini -am
-Pgemini-dev`). Update the proposal doc's status if appropriate. Final grep sweep across all
`pom.xml` for stale `<relativePath>`/`<module>` references and any lingering `app/` /
`integrations/` paths in scripts or CI. Confirm a clean `mvn clean install` green from the new
layout and rendered output identical to baseline.
**Verify (final gate):** `mvn clean install` green from new layout; `mvn -pl cli -am -Pjlink
package` builds the jlink image + `--version` runs; `diff -r build/ .agents/tmp/expected-output/`
returns no differences; `grep -rn 'app/\|integrations/' --include='pom.xml'` finds no stale paths.

---

## Calibration decisions (resolved)

1. **Verification bar:** green build + **byte-identical rendered output** per task vs a
   pre-restructure baseline snapshot. No line-coverage % (this plan introduces no new logic).
2. **Risk overrides:** Tasks 3 and 4 promoted Med → **High** (cross-module path rewiring is
   release-critical). Tasks 1, 2 stay High; Tasks 5–7 stay Low. → De-risk & Harden cycle applies
   to Tasks 1–4; Tasks 5–7 are single-pass.
3. **Future-target placeholders:** `web/`, `desktop/{win,linux,mac}`, `opencode/` are **not**
   created in this plan — no build wiring exists for them. They are added when a real target arrives.
