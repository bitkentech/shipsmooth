# plan-77 — Codex CLI support (third agent host)

## Context

**Backlog feature:** Let users run the shipsmooth agent-coding workflow under
OpenAI's **Codex CLI**, alongside the existing Claude and Gemini hosts. (No
external Linear issue; tracked here.)

Today shipsmooth renders two host variants from one set of JTE skill templates:

- **Claude** — a Claude Code plugin (`.claude-plugin/{plugin,marketplace}.json`,
  `SessionStart` hook).
- **Gemini** — a Gemini CLI extension (`gemini-extension.json`, TOML commands,
  `SessionStart` hook).

Codex CLI shares **neither** manifest model.

### Research findings (authoritative, June 2026)

1. **Codex custom prompts are DEPRECATED** in favour of **Codex skills**
   (`developers.openai.com/codex/skills`). A Codex skill is a directory under
   `~/.codex/skills/<name>/` containing a **`SKILL.md`** with YAML frontmatter
   (**required** `name` + `description`) and a markdown body. The `description`
   field is the *trigger* — Codex loads the skill by matching it, there is no
   slash-command or session-start wiring needed for activation.

2. **This is the SAME shape shipsmooth already renders for Claude.** The existing
   `start/SKILL.jte.md` → `SKILL.md` pipeline (with `skill.frontmatter`) maps
   almost directly onto `~/.codex/skills/shipsmooth/SKILL.md`. Codex support is
   therefore *much* closer to the Claude render than to the Gemini extension —
   no new manifest format, no TOML commands.

3. **Codex has NO `SessionStart` hook.** The Claude/Gemini install path runs
   `install-shipsmooth.sh` on every session start to bootstrap the jlink runtime
   (plan-76). Codex offers no equivalent per-session hook, so runtime
   bootstrapping must move to a **one-time install** step (the design is settled
   in Task 6 below; flagged "decide during planning" at intake, resolved here).

### Decisions locked at intake

- **Surface:** Codex **skills** (`~/.codex/skills/`), not the deprecated custom
  prompts and not an `AGENTS.md`-only projection.
- **Template dispatch:** replace the binary `isGemini()` if/else with a
  **per-platform fragment selector**, so claude/gemini/codex each resolve their
  own `shared/workflow/<host>/` fragment. (12 branch sites today.)
- **Install/activation:** resolved in this plan (Task 6): a one-time installer
  that copies the rendered skill into `~/.codex/skills/` and bootstraps the
  runtime once, since there is no per-session hook to lean on.

## Goals / Non-goals

**Goals**
- A `codex` render variant that emits `~/.codex/skills/shipsmooth/SKILL.md`
  (frontmatter + body) from the existing JTE templates, with Codex-specific
  workflow fragments where the host semantics differ from Claude/Gemini.
- A self-contained `codex/` Gradle module + `assembleCodexProd`/`Dev` payload,
  mirroring the structure (not the manifest contents) of the `gemini/` module.
- A one-time install path that lands the skill under `~/.codex/skills/` and
  bootstraps the jlink runtime once (no SessionStart hook available).
- The binary `isGemini()` template split refactored into a clean per-platform
  dispatch so adding a 4th host later is additive, not another nested branch.

**Non-goals**
- Codex custom prompts / slash commands (deprecated; skills auto-trigger by
  description).
- A Windows Codex build (Codex CLI is posix-first here; mirror the existing
  `Target.guard()` rule that pins Windows to Claude only).
- Changing the Claude or Gemini payloads' behaviour (additive only — their
  rendered output must stay byte-identical; a parity check guards this).
- `publishRelease` (human cuts releases per the established process).

## Design

### Extension points (where a 3rd host plugs in)

The render engine already isolates host differences behind three seams; Codex
adds a case to each:

1. **`Platform` sealed interface** (`skills/pkg/.../Platform.java`) —
   `permits Platform.Claude, Platform.Gemini`. Add `Platform.Codex` with
   `id() = "codex"` and `skillFragmentDir() = "start/codex"` (today there is no
   `start/<host>` fragment dir on disk — only `start/SKILL.jte.md` — so all three
   ids currently resolve the same base; the field exists for symmetry and future
   host-specific start fragments). `from(String)` gains the `"codex"` case.

2. **`PluginModel` platform flag** — today a single `boolean gemini` /
   `isGemini()` drives template branching. This binary flag does not extend to a
   3rd host. Replace it with a **platform discriminator** the templates can switch
   on — e.g. carry `platformId` (`"claude"`/`"gemini"`/`"codex"`) on the model and
   expose `platformDir()` returning the `shared/workflow/<host>` segment. Keep
   `isGemini()`/`isCodex()` thin convenience accessors **only** where a fragment
   genuinely needs a boolean; the structural dispatch goes through `platformDir()`.

3. **`Os` cli-bin / cache** — `cliBinPath()` and `cacheSubdir()` already accept
   the plugin name; Codex reuses the posix `cliBinPath`. The cache subdir for
   Codex is `shipsmooth` (same as Claude posix), so no `Os` change is expected —
   verify during Task 1.

### Template dispatch refactor (the core risk)

`shared/parallel-execution.jte.md` (10 branches) and
`shared/workflow/phase2-execute.jte.md` (2 branches) currently read:

```jte
@if(model.isGemini())
@template.shared.workflow.gemini.permission-consent(model = model)
@else
@template.shared.workflow.claude.permission-consent(model = model)
@endif
```

JTE template paths are static (no dynamic `@template.<expr>`), so a 3-way host
split is still expressed as `@if/@elseif/@else` — but driven by a single
`platformDir()`/id discriminator rather than `isGemini()`, so the branch reads as
an explicit host switch and each host names its own fragment. Each of the 13
`shared/workflow/{claude,gemini}/*.jte.md` fragments gets a `codex/` sibling.

**Codex fragment content.** For the first cut, Codex's host semantics most
closely match **Gemini** (external CLI, explicit permission/consent prompts,
shell-driven task sequencing) rather than Claude (in-process Task tool). The 13
`codex/` fragments start as copies of the `gemini/` fragments and are then
adjusted where Codex's actual command surface differs (e.g. agent-dispatch,
ledger-watch, resolver-call invocations). Getting each fragment's commands right
for Codex is the substantive content work and is split across tasks by fragment
family, not done as one blob.

### Render variant + module

- `skills/pkg/build.gradle.kts`: add `codexDevSpec`/`codexProdSpec` (`.copy()` of
  the claude specs with `buildPlatform = "codex"`, Codex frontmatter, and the
  Codex install hook handling per Task 6). Register `renderCodexDev`/`Prod`.
- New `codex/` module (`settings.gradle.kts` += `include("codex")`): mirrors the
  `gemini/` module's structure — a `registerCodexMeta` factory + the shared
  `registerPayloadAssembly`/`registerPayloadSync` helpers from buildSrc — but the
  payload is a `~/.codex/skills/`-shaped tree (`skills/shipsmooth/SKILL.md`), not
  a `gemini-extension.json` + commands tree. No `.claude-plugin/` metadata.
- `packaging/build.gradle.kts`: extend `validateRelease` + the per-platform
  package step to cover the codex payload dir (`build-codex/`), mirroring the
  `build-gemini/` wiring.

### Install / activation (no SessionStart hook)

Codex auto-loads the skill by `description`, so **activation** needs nothing once
`~/.codex/skills/shipsmooth/SKILL.md` is present. What still needs bootstrapping
is the **jlink runtime** the skill's CLI commands invoke. With no per-session
hook, the plan ships a **one-time `install-shipsmooth.sh`-style installer** that
(a) copies the rendered skill dir into `~/.codex/skills/` and (b) runs the same
runtime-bootstrap the plan-76 posix script already performs, but once at install
time rather than per session. The skill body references the bootstrapped launcher
path (the existing `Os.Posix.cliBinPath`, `${XDG_CACHE_HOME:-~/.cache}/...`). The
exact installer entry point (reuse plan-76's script with an `--into-codex` mode
vs. a thin codex-specific wrapper) is settled in Task 6.

## Tasks

### Task 1: `Platform.Codex` + per-platform discriminator on the model [High]

*Depends-on:*

Highest-leverage structural change; everything else renders through it. Add
`Platform.Codex` to the sealed interface (`id`, `skillFragmentDir`, `from`
case). Replace the binary `boolean gemini` on `PluginModel` with a platform
discriminator (`platformId` + `platformDir()`), keeping thin `isGemini()`
/`isCodex()` accessors for genuine boolean fragments. Update `Target` to populate
it and extend `Target.guard()` (Windows still Claude-only; Codex is posix-only —
reject `codex`+windows). Java tests: `Platform.from("codex")`, `platformDir()`
for all three, guard rejects codex+windows. **Claude/Gemini render output must
stay byte-identical** — this task only adds a case, it must not change existing
branches' results.

### Task 2: Refactor the binary `isGemini()` template branches to host dispatch [High]

*Depends-on:* 1

Convert the 12 `isGemini()` if/else sites in `shared/parallel-execution.jte.md`
and `shared/workflow/phase2-execute.jte.md` into explicit host switches driven by
the Task-1 discriminator (`@if`/`@elseif`/`@else` on platform id). No Codex
fragments exist yet, so each site's `codex` arm initially points at the **gemini**
fragment (Codex ≈ Gemini, per Design) — a deliberate placeholder replaced in
Tasks 4–5. Regression guard: re-render claude-prod and gemini-prod and diff
against the pre-change output — must be **byte-identical** (proves the refactor is
behaviour-preserving for the two existing hosts).

### Task 3: `codexDevSpec`/`codexProdSpec` render variants [Medium]

*Depends-on:* 2

Add the Codex render specs in `skills/pkg/build.gradle.kts` (`.copy()` of the
claude specs: `buildPlatform = "codex"`, Codex `name`/`description` frontmatter
matching the SKILL.md trigger model, posix os, install-hook handling deferred to
Task 6). Register `renderCodexDev`/`renderCodexProd`. Verify the render emits
`skills/shipsmooth/SKILL.md` with valid YAML frontmatter (`name: shipsmooth`,
`description: …`) and the workflow body. At this point Codex content == Gemini
content (placeholder fragments); that is expected.

### Task 4: Codex fragment set — execution/permission family [Medium]

*Depends-on:* 3

Create `shared/workflow/codex/` and author the **execution-path** fragments
(`permission-consent`, `task-command-sequence-{independent,dependent}`,
`background-execution`, `set-commit-{hardening,low-risk}`) for Codex's actual
command surface, diverging from the gemini copies where Codex's CLI invocation /
consent model differs. Point the Task-2 `codex` arms for these fragments at the
new files. Re-render codex-prod and eyeball the affected sections; assert
claude/gemini output still byte-identical.

### Task 5: Codex fragment set — agent-dispatch/ledger family [Medium]

*Depends-on:* 4

Author the remaining `shared/workflow/codex/` fragments
(`agent-dispatch-{independent,dependent}`, `agent-instruction`,
`agent-resolver-call`, `resolver-complete-cmd`, `ledger-watch-cmd`,
`file-overlap-check`) for Codex. This is the parallel-execution / multi-agent
surface — verify the dispatched-agent and resolver invocations name the Codex
launcher correctly. Repoint the corresponding Task-2 `codex` arms; remove the
last gemini-fragment placeholders. Re-render; claude/gemini parity check holds.

### Task 6: One-time Codex installer (no SessionStart hook) [Medium]

*Depends-on:* 3

Resolve the activation design (open at intake). Codex auto-triggers by
`description`, so only the **runtime bootstrap** needs handling without a
per-session hook. Provide a one-time installer that copies the rendered skill into
`~/.codex/skills/shipsmooth/` and bootstraps the jlink runtime once — reusing
plan-76's posix `install-shipsmooth.sh` logic (an `--into-codex` mode that targets
the codex skills dir, or a thin codex wrapper that calls it). The Codex render spec
(Task 3) wires the skill body to the bootstrapped `Os.Posix.cliBinPath`. Test:
installer lands `SKILL.md` under the codex skills dir and the runtime-bootstrap
path matches the launcher path the skill body references. Document that there is
no per-session hook (one-time install is intentional).

### Task 7: `codex/` Gradle module + assembleCodex{Dev,Prod} [Medium]

*Depends-on:* 5, 6

Add the `codex/` module (`settings.gradle.kts` += `include("codex")`) mirroring
`gemini/`'s structure: a `registerCodexMeta` factory and the buildSrc
`registerPayloadAssembly` (dev) / `registerPayloadSync` (prod) helpers, producing
a `~/.codex/skills/`-shaped payload (no `gemini-extension.json`, no commands TOML,
no `.claude-plugin/`). `assembleCodexDev` → `build-codex-dev/`, `assembleCodexProd`
→ `build-codex/`. Run both; assert the payload tree contains
`skills/shipsmooth/SKILL.md` + the installer + the runtime backup, and the
overlap-check passes (dev path) / Sync is sole writer (prod path).

### Task 8: Release wiring + parity gate + docs + version bump [Low]

*Depends-on:* 7

Extend `packaging/build.gradle.kts` `validateRelease` and the per-platform package
step to cover `build-codex/` (mirror the `build-gemini/` lines). Add a CI/build
parity assertion that claude-prod and gemini-prod renders are unchanged by this
plan (lock Task 2's invariant). Document Codex support (install via the one-time
installer; no Node and no per-session hook required) wherever the install story
lives (README / DEVELOPMENT.md). Bump the patch version per the release process.
Do **not** run `publishRelease`.

## Risk summary (pre-calibration defaults)

| Task | Default risk | Why |
|---|---|---|
| 1 | High | Sealed-interface + model discriminator; must not perturb existing two hosts. |
| 2 | High | 12-site template refactor; byte-identical claude/gemini output is the gate. |
| 3 | Medium | New render specs; SKILL.md frontmatter must satisfy Codex's trigger model. |
| 4 | Medium | Codex execution/consent fragment semantics (real command surface). |
| 5 | Medium | Codex multi-agent/resolver fragment semantics. |
| 6 | Medium | Activation design w/o SessionStart hook; one-time runtime bootstrap. |
| 7 | Medium | New Gradle module + payload assembly/overlap wiring. |
| 8 | Low | Release-dir wiring, parity gate lock, docs, version bump. |

Dependency note: 1 → 2 is the hard structural spine (both High, in order). Tasks
4, 5, 6 all depend on 3 and can proceed in parallel once the render variant
exists; 7 joins 5+6; 8 is the closeout. Risk-sorted order respects the
dependency chain (no Low task gates a High one).
