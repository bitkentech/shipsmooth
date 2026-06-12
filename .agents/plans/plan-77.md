# plan-77 — Codex CLI support (third agent host)

*Version: plan-77-v2. v2 corrects v1 after the Task 1 de-risk built a real Codex
plugin by hand: Codex uses a first-class **plugin** model (closest to Claude, not
Gemini) and **does** support a `SessionStart` hook — so the v1 "one-time installer"
(old Task 6) is dropped in favour of a per-session hook, and the payload is a
plugin tree (`.codex-plugin/plugin.json` + bundled `skills/` + `hooks/hooks.json`),
not a loose `~/.codex/skills/` drop.*

## Context

**Backlog feature:** Let users run the shipsmooth agent-coding workflow under
OpenAI's **Codex CLI**, alongside the existing Claude and Gemini hosts. (No
external Linear issue; tracked here.)

Today shipsmooth renders two host variants from one set of JTE skill templates:

- **Claude** — a Claude Code plugin (`.claude-plugin/{plugin,marketplace}.json`,
  bundled `skills/`, `hooks/hooks.json` SessionStart hook).
- **Gemini** — a Gemini CLI extension (`gemini-extension.json`, TOML commands,
  `SessionStart` hook).

### Research findings (authoritative, June 2026 — corrected at v2)

Source: `developers.openai.com/codex/plugins/build`,
`developers.openai.com/codex/skills`. v2 supersedes v1's premises after the
**Task 1 de-risk** built a real Codex plugin by hand.

1. **Codex ships a first-class PLUGIN model — closest to Claude, not Gemini.**
   A Codex plugin is a directory with:
   - `.codex-plugin/plugin.json` — JSON manifest, required fields `name`
     (kebab-case), `version`, `description`, `skills: "./skills/"`.
   - `skills/<name>/SKILL.md` — bundled skill(s): YAML frontmatter (**required**
     `name` matching the folder + `description`) + markdown body.
   - optional `agents/openai.yaml` (per-skill + plugin-root UI metadata),
     `hooks/hooks.json`, `.mcp.json` (bundled MCP server), `.app.json`, `assets/`.

2. **Codex DOES support a `SessionStart` hook** (v1 was wrong). `hooks/hooks.json`
   uses the **same event schema as Claude**:
   `{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"…"}]}]}}`,
   with `${PLUGIN_ROOT}` as the plugin-root placeholder. So the jlink runtime can
   be bootstrapped **per session** exactly like Claude/Gemini (reusing plan-76's
   `install-shipsmooth.sh`) — **no one-time installer is needed**. The v1 "Task 6
   one-time installer" is dropped.

3. **Codex custom prompts are DEPRECATED** in favour of skills — confirming the
   skill-based surface, but the skill is now *bundled inside the plugin* rather
   than dropped loose into `~/.codex/skills/`.

4. **The SKILL.md body is the SAME shape shipsmooth already renders.** The
   existing `start/SKILL.jte.md` → `SKILL.md` pipeline maps directly onto the
   plugin's `skills/start/SKILL.md`. The skill is named **`start`** (folder
   `skills/start/`, frontmatter `name: start`) — consistent with the Claude/Gemini
   hosts; Codex triggers it by `description`, not by name.

### De-risk findings — verified against `codex-cli 0.139.0` (this box)

The Task 1 de-risk built `build-codex/` by hand and ran it through a real Codex
install. Corrections beyond the docs (docs were misleading on the layout):

- **Marketplace layout is exact:** the marketplace *root* holds
  `.agents/plugins/marketplace.json` (NOT a loose `marketplace.json`); plugins
  live under `<root>/plugins/<name>/`. The de-risk root is `build-codex/`:
  ```
  build-codex/
  ├── .agents/plugins/marketplace.json     # name/interface/plugins[] schema
  └── plugins/shipsmooth/                   # plugin (source.path = ./plugins/shipsmooth)
      ├── .codex-plugin/plugin.json
      └── skills/start/SKILL.md
  ```
- **Install flow is CLI, not `cp -R`:** `codex plugin marketplace add <root>` →
  `codex plugin list` (shows `start`'s plugin) → `codex plugin add
  <plugin>@<marketplace>`. Codex caches the plugin to
  `~/.codex/plugins/cache/<marketplace>/<plugin>/<version>/`. Re-running
  `plugin add` refreshes the cache after a re-render (dev-iteration path; a local
  marketplace is read live from disk, `marketplace upgrade` is Git-only).
- **`agents/openai.yaml` and `hooks/` are OPTIONAL:** the plugin installed,
  enabled, and `/skills` listed it with neither present. So the SessionStart hook
  (Task 6) is additive — skill activation does not depend on it.
- **End-to-end proven:** in a real session `/skills` showed `start`; triggering it
  shelled out to the installed runtime at
  `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-<ver>/bin/shipsmooth` (the exact
  `Os.Posix.cliBinPath`). The skill→runtime round-trip works. (Two unrelated
  environment issues surfaced and are out of plan-77 scope: a `plan resume` NPE on
  a malformed pre-existing plan XML with a null `<status>`, and Codex's bwrap
  sandbox failing under Ubuntu 24.04 AppArmor `apparmor_restrict_unprivileged_userns=1`.)

### Decisions locked (intake + v2 de-risk)

- **Surface:** a Codex **plugin** (`.codex-plugin/plugin.json` + bundled
  `skills/start/SKILL.md` + `hooks/hooks.json`), installed via
  `~/.agents/plugins/marketplace.json`. Not loose `~/.codex/skills/`, not the
  deprecated custom prompts, not `AGENTS.md`-only.
- **Template dispatch:** replace the binary `isGemini()` if/else with a
  **per-platform fragment selector**, so claude/gemini/codex each resolve their
  own `shared/workflow/<host>/` fragment. (12 branch sites today.)
- **Install/activation:** **per-session SessionStart hook** (same as Claude),
  reusing plan-76's `install-shipsmooth.sh` with a `${PLUGIN_ROOT}` placeholder.
  The v1 one-time-installer task is removed.

## Goals / Non-goals

**Goals**
- A `codex` render variant that emits a Codex **plugin** payload —
  `.codex-plugin/plugin.json` + `skills/start/SKILL.md` + `hooks/hooks.json`
  — from the existing JTE templates, with Codex-specific workflow fragments where
  host semantics differ from Claude/Gemini.
- A self-contained `codex/` Gradle module + `assembleCodexProd`/`Dev` payload,
  reusing the claude/gemini assembly machinery (Codex's plugin shape is closest
  to Claude's).
- Per-session runtime bootstrap via a Codex `SessionStart` hook reusing plan-76's
  `install-shipsmooth.sh` (`${PLUGIN_ROOT}` placeholder).
- The binary `isGemini()` template split refactored into a clean per-platform
  dispatch so adding a 4th host later is additive, not another nested branch.

**Non-goals**
- Codex custom prompts / slash commands (deprecated; the skill is bundled in the
  plugin and triggers by description).
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

**Codex fragment content (audited — supersedes the initial "Codex ≈ Gemini" guess).**
An audit of the 13 claude/gemini fragments against Codex's real tool surface found
the host differences split on two axes:
- *Shell axis (`$(...)` command substitution):* Codex's shell runs `$(...)` (the
  de-risk's bwrap shell proved this) → Codex is **claude-like**, NOT gemini-like
  (gemini avoids `$(...)` and hand-splits into capture-then-substitute steps).
- *Tool-vocabulary axis:* each host names its own tools — Claude `Agent` /
  `Bash`+`run_in_background` / `Monitor` / `.claude/settings.json`; Gemini
  `invoke_agent` / `run_shell_command`+`is_background` / `read_background_output`.
  Codex has its **own** vocabulary again.

Two **decisions** from the audit:
1. **Author all 13 `codex/` fragments** (a full per-host set, even where 3 would be
   byte-identical to claude — `set-commit-hardening`, `set-commit-low-risk`,
   `file-overlap-check`). Uniform structure; no cross-host fragment sharing.
2. **Codex parallel execution = sequential-only (this cut).** Codex's subagent model
   is fundamentally different from both hosts: subagents are spawned by **natural
   language** (no `Agent`/`invoke_agent` tool call) using built-in `worker`/`explorer`
   agents (`~/.codex/agents/*.toml`), with `agents.max_threads`=6 / `agents.max_depth`=1.
   Rather than port a parallel-dispatch flow that can't be verified end-to-end yet
   (the bwrap sandbox is currently AppArmor-blocked on this box), the Codex
   agent-dispatch / parallel fragments instruct the user that parallel dispatch is
   **not yet supported on Codex** and to fall back to the **sequential per-task loop**
   the skill already supports. Adapting to Codex's real NL-spawn parallel model is a
   follow-up once it's verifiable.

### Codex plugin payload shape (confirmed by the Task 1 de-risk)

```
<marketplace-root>/                       # → the dir passed to `codex plugin marketplace add`
├── .agents/plugins/marketplace.json      # name/interface/plugins[]; source.path=./plugins/shipsmooth
└── plugins/
    └── shipsmooth/                        # the PLUGIN (folder = plugin name)
        ├── .codex-plugin/
        │   └── plugin.json                # name=shipsmooth, version, description, skills="./skills/"
        ├── skills/
        │   └── start/                     # the SKILL (folder = skill name = `start`)
        │       └── SKILL.md               # name: start + description + workflow body
        └── hooks/
            ├── hooks.json                 # SessionStart → sh ${PLUGIN_ROOT}/hooks/install-shipsmooth.sh
            └── install-shipsmooth.sh      # plan-76 posix bootstrap (reused)
```

The `.agents/plugins/marketplace.json` is the registration entry point (its
location confirmed by the de-risk — NOT a loose root-level `marketplace.json`).
This is structurally the **Claude plugin** shape (manifest + bundled `skills/` +
`hooks/hooks.json`), with a different manifest filename/dir
(`.codex-plugin/plugin.json` vs `.claude-plugin/plugin.json`) and the marketplace
file living one level up in the marketplace root, not inside the plugin.

### Render variant + module

- `skills/pkg/build.gradle.kts`: add `codexDevSpec`/`codexProdSpec` (`.copy()` of
  the claude specs with `buildPlatform = "codex"`, Codex frontmatter `name: start`,
  and the Codex SessionStart hook string with `${PLUGIN_ROOT}`).
  Register `renderCodexDev`/`Prod`. Reuse the existing `HooksRenderer` (Codex uses
  the same hooks.json schema as Claude).
- New `codex/` module (`settings.gradle.kts` += `include("codex")`): mirrors the
  **claude** module's structure — a `registerCodexMeta` factory emitting
  `.codex-plugin/plugin.json` + the marketplace.json sibling — plus the shared
  `registerPayloadAssembly`/`registerPayloadSync` helpers from buildSrc. No
  `gemini-extension.json`, no TOML commands, no `.claude-plugin/` metadata.
- `packaging/build.gradle.kts`: extend `validateRelease` + the per-platform
  package step to cover the codex payload dir, mirroring the `build-gemini/`
  wiring. (Note: the real render output dir must NOT clobber the hand-built
  de-risk artifact at `build-codex/shipsmooth/`; pick `build-codex-dev/` for dev
  and a staged dir for prod, or relocate the de-risk artifact — settled in Task 7.)

### Install / activation (per-session SessionStart hook)

Corrected at v2: Codex plugins **do** support a `SessionStart` hook
(`hooks/hooks.json`, same schema as Claude, `${PLUGIN_ROOT}` placeholder). So the
runtime bootstraps per session exactly like Claude/Gemini — the plan reuses
plan-76's `install-shipsmooth.sh` with the hook string
`sh "${PLUGIN_ROOT}/hooks/install-shipsmooth.sh" shipsmooth <version>`. The skill
body references the bootstrapped launcher via the existing `Os.Posix.cliBinPath`
(`${XDG_CACHE_HOME:-~/.cache}/...`). **No one-time installer** (v1 Task 6 dropped).
Skill activation itself needs nothing beyond the plugin being installed + enabled —
Codex triggers the bundled skill by its `description`.

## Tasks

### Task 1: De-risk — hand-build a Codex plugin + `Platform.Codex` discriminator [High]

*Depends-on:*

**De-risk slice (DONE & VERIFIED end-to-end):** hand-built a real Codex plugin at
`build-codex/` and proved it against `codex-cli 0.139.0` — `.codex-plugin/plugin.json`
(name/version/description/skills), `skills/start/SKILL.md` (the real gemini-prod
start workflow body with Codex `name: start` frontmatter), `.agents/plugins/marketplace.json`,
and a `README.md`. Verified: `codex plugin marketplace add` → `plugin list` →
`plugin add` installs+enables; `/skills` lists `start`; triggering it shells out
to the installed runtime at `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-<ver>/bin/shipsmooth`.
The de-risk corrected the plan to v2 (plugin model + real SessionStart hook) and
nailed the exact marketplace layout (`.agents/plugins/marketplace.json`, plugins
under `plugins/<name>/`) — see Context "De-risk findings".

**Code slice (TODO):** the structural change everything else renders through. Add
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
claude specs: `buildPlatform = "codex"`, Codex `name: start` frontmatter
matching the SKILL.md trigger model, posix os, the Codex SessionStart hook string
`sh "${PLUGIN_ROOT}/hooks/install-shipsmooth.sh" shipsmooth <version>` per Task 6).
Register `renderCodexDev`/`renderCodexProd`. Verify the render emits
`skills/start/SKILL.md` with valid YAML frontmatter (`name: start`,
`description: …`) + workflow body, and `hooks/hooks.json` carrying the SessionStart
hook. At this point Codex content == Gemini content (placeholder fragments); that
is expected. Match the hand-built de-risk artifact's `skills/start/SKILL.md`.

### Task 4: Codex fragment set — execution/permission family [Medium]

*Depends-on:* 3

Create `shared/workflow/codex/` and author the **execution-path** fragments
(`permission-consent`, `task-command-sequence-{independent,dependent}`,
`background-execution`, `set-commit-{hardening,low-risk}`). Per the audit: the
`set-commit-*` fragments are claude-like (`$(git rev-parse HEAD)` inline works on
Codex's shell); `task-command-sequence-*` and `background-execution` keep the
`$(...)` shell style but, per the sequential-only decision, their agent-dispatch
lines tell the user parallel dispatch is not yet supported on Codex; `permission-consent`
drops the Claude `.claude/settings.json` patch (Codex has its own sandbox/approval
model, no settings file to patch). Repoint the Task-2 `codex` arms for these six at
the new files (switch from the gemini placeholder to a true 3-way codex branch).
Re-render codex-prod and eyeball; assert claude/gemini output still byte-identical.

### Task 5: Codex fragment set — agent-dispatch/ledger family [Medium]

*Depends-on:* 4

Author the remaining seven `shared/workflow/codex/` fragments
(`agent-dispatch-{independent,dependent}`, `agent-instruction`,
`agent-resolver-call`, `resolver-complete-cmd`, `ledger-watch-cmd`,
`file-overlap-check`). Per the audit: `file-overlap-check` is claude-like (the
`for` loop + `$(...)` works on Codex); `ledger-watch-cmd` and `resolver-complete-cmd`
keep `$(git rev-parse --show-toplevel)` but there is **no Codex `Monitor` tool**, so
ledger-watch runs as a normal blocking shell command. The four `agent-*` fragments
are the sequential-only path: state that Codex parallel subagent dispatch is not yet
supported and the Lead Agent should run the per-task loop sequentially in the main
context (no `Agent`/`invoke_agent` call). Repoint the remaining Task-2 `codex` arms;
remove the last gemini-fragment placeholders so every `codex` arm names a `codex/`
fragment. Re-render; claude/gemini parity check holds.

### Task 6: Codex `SessionStart` hook (runtime bootstrap) [Medium]

*Depends-on:* 3

Codex plugins support `hooks/hooks.json` with the **same SessionStart schema as
Claude** (corrected at v2; the de-risk confirmed hooks are optional, so this is a
clean additive slice). Wire the Codex render to emit `hooks/hooks.json` whose
SessionStart command is `sh "${PLUGIN_ROOT}/hooks/install-shipsmooth.sh" shipsmooth
<version>` and to copy plan-76's `install-shipsmooth.sh` next to it (reuse
`Os.Posix.hookCommand`'s script-writing side-effect; the only delta is the
`${PLUGIN_ROOT}` placeholder vs `${CLAUDE_PLUGIN_ROOT}`/`${extensionPath}`). The
skill body already references the bootstrapped launcher via `Os.Posix.cliBinPath`
(de-risk verified the path resolves to the real runtime). Test: rendered
`hooks.json` carries the SessionStart command with `${PLUGIN_ROOT}` and
`install-shipsmooth.sh` ships alongside. (Manifest + marketplace emission moved to
Task 7 — they belong with the module's payload assembly.)

### Task 7: `codex/` Gradle module + assembleCodex{Dev,Prod} [Medium]

*Depends-on:* 5, 6

Add the `codex/` module (`settings.gradle.kts` += `include("codex")`) mirroring
the **claude** module's structure (Codex's plugin shape is closest to Claude's): a
`registerCodexMeta` factory that emits the **non-rendered plugin metadata** —
`.codex-plugin/plugin.json` (name/version/description/skills, valid JSON, four
required fields) and the `.agents/plugins/marketplace.json` registration file
(name/interface/plugins[] with `source.path = ./plugins/shipsmooth`) — plus the
buildSrc `registerPayloadAssembly` (dev) / `registerPayloadSync` (prod) helpers
that fold in the Task-3 render (skills/) and Task-6 hooks/. No
`gemini-extension.json`, no commands TOML, no `.claude-plugin/`. The assembled
payload must match the de-risk layout: `<root>/.agents/plugins/marketplace.json`
+ `<root>/plugins/shipsmooth/{.codex-plugin/plugin.json, skills/start/SKILL.md,
hooks/{hooks.json,install-shipsmooth.sh}}`. **Output dirs:** `assembleCodexDev` →
`build-codex-dev/`; `assembleCodexProd` → a staged prod dir — must NOT overwrite
the hand-built de-risk artifact at `build-codex/` (use a distinct prod dir, or
relocate/remove the de-risk tree first; both `build-codex/` and `build-codex-dev/`
are already gitignored). Run both; assert the payload tree and the overlap-check
(dev) / Sync-is-sole-writer (prod); a `python3 -m json.tool` on plugin.json +
marketplace.json gates JSON validity.

### Task 8: Release wiring + parity gate + docs + version bump [Low]

*Depends-on:* 7

Extend `packaging/build.gradle.kts` `validateRelease` and the per-platform package
step to cover the codex payload dir (mirror the `build-gemini/` lines). Add a
CI/build parity assertion that claude-prod and gemini-prod renders are unchanged by
this plan (lock Task 2's invariant). Document Codex support — install via
`codex plugin marketplace add <root>` then `codex plugin add shipsmooth@<marketplace>`
(the de-risk-verified flow, not manual `cp -R`); the SessionStart hook bootstraps
the runtime, no Node required — wherever the install story lives (README /
DEVELOPMENT.md). Bump the patch version per the release process. Do **not** run
`publishRelease`.

## Risk summary (pre-calibration defaults)

| Task | Default risk | Why |
|---|---|---|
| 1 | High | De-risk plugin (done) + sealed-interface/model discriminator; must not perturb existing two hosts. |
| 2 | High | 12-site template refactor; byte-identical claude/gemini output is the gate. |
| 3 | Medium | New render specs; SKILL.md frontmatter must satisfy Codex's trigger model. |
| 4 | Medium | Codex execution/consent fragment semantics (real command surface). |
| 5 | Medium | Codex multi-agent/resolver fragment semantics. |
| 6 | Medium | SessionStart hook (runtime bootstrap); `${PLUGIN_ROOT}` placeholder delta. |
| 7 | Medium | New Gradle module + manifest/marketplace emission + payload assembly; output-dir vs de-risk artifact. |
| 8 | Low | Release-dir wiring, parity gate lock, docs, version bump. |

Dependency note: 1 → 2 is the hard structural spine (both High, in order). Tasks
4, 5, 6 all depend on 3 and can proceed in parallel once the render variant
exists; 7 joins 5+6; 8 is the closeout. Risk-sorted order respects the
dependency chain (no Low task gates a High one).
