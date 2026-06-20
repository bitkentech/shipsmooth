# plan-85 — shipsmooth data folder naming & first-run UX

## Context

This plan redesigns **how shipsmooth names and locates its data tree** — both the
external folder (where standalone state lives) and the internal layout under it —
and defines the **first-run user experience** for choosing where state goes.

It is the concrete realisation of the long-standing *standalone-as-default*
direction: shipsmooth should be **non-intrusive by default**, not writing a folder
into the user's repo unless they opt into in-repo mode.

Backlog / feature link: _TODO — link the permanent backlog feature issue (e.g. a
PB-xxx in "shipsmooth — Backlog & Roadmap") before `plan init`._

### Current state of the world

- `ShipsmoothDataLocator` (core) hardcodes `.agents/plans` as `PLANS_DIR`, resolved
  against `stateRoot`. It is the single source of truth for path construction.
- `stateRoot` = `repoRoot` in `InRepo` (default) mode; = a config-specified
  `stateDir` in `Standalone` mode. So today both modes yield `<root>/.agents/plans`.
- Standalone requires an explicit `~/.config/shipsmooth/ss-config.toml` entry with a
  literal `stateDir`. No entry ⇒ `InRepo`. Matching is on the `(localPath, remoteUrl)`
  pair (`ProjectDataStoreResolver.matchEntry`).
- Two live TODOs in `ProjectDataStore.java`: `.agents` is hardcoded in the standalone
  guard; and there is no default `stateDir` derivation.
- `ProjectDataStore.Standalone.init()` currently hard-errors if `.agents/` exists, and
  auto-creates/git-inits a missing state dir.

### Naming convention rationale

`.agents/` was an ad-hoc name. In 2026 the ecosystem is converging on `.agents/` as a
dir for **human-authored, committed agent config** (skills, MCP servers), alongside
`AGENTS.md` as the instruction-file standard. Shipsmooth's data is **machine-generated
transient state** (plan files, task XML) — a different animal. Cohabiting in `.agents/`
risks collision and semantic confusion. Therefore shipsmooth moves to a **tool-owned**
`.shipsmooth/` (mirrors the `.claude/`, `.cursor/` tool-specific pattern), which can't
clash with the emerging `.agents/`-as-config meaning.

## Settled design

### Naming & layout
- Folder is **`.shipsmooth/`** (replaces `.agents/`).
- `plans/` always hangs off `stateRoot`. The only difference between modes is what
  `stateRoot` is:
  - **In-repo:** `stateRoot = repoRoot/.shipsmooth` → `…/.shipsmooth/plans/…`
  - **Standalone:** `stateRoot = stateDir` → `…/<stateDir>/plans/…` (no dot-folder
    segment — the dedicated dir *is* the root).
- **No back-compat with `.agents/`.** Existing folders are renamed by hand; the new
  code only ever looks for `.shipsmooth/`. (Renaming *this* repo's own `.agents/` tree
  is plan work — tests/`.gitignore`/SKILL prose reference it — not a side chore.)

### Division of labor (CLI = brain, skill = mouth)
- The CLI runs **non-interactively** under the agent; it must never prompt on stdin.
- `resolve()` detects the situation and decides per the rules, returning either a
  resolved state root, **or** a structured *needs-decision + options* result that also
  tells the skill which option is recommended.
- The **skill** renders those options in chat, gets a real user answer, and re-invokes
  the CLI to act on it (create the dir, write the config entry).
- **Steady state is silent:** a settled config or a present in-repo folder resolves with
  no skill round-trip.

### Repo identity
- Config matches on the `(localPath, remoteUrl)` pair (unchanged from today). A distinct
  checkout dir is its own entry; a moved/renamed repo or a different remote at the same
  path re-handshakes. No cross-clone state sharing.

### The branch table (every config × filesystem combination)

| Situation | CLI action |
|---|---|
| No config, no state anywhere (clean first run) | Relay → ask (external **recommended**, in-repo offered) → create chosen + **write config entry** |
| Config + external dir exists & consistent | Silent → external |
| Config + external dir **missing** | Relay → ask "create it?" → create |
| In-repo `.shipsmooth/` present, no config | Silent → in-repo |
| Both in-repo `.shipsmooth/` **and** config'd state repo | **Config wins** → silent → external |
| Genuinely ambiguous / corrupt | **Hard fail** (tell the user to fix by hand) |

### Invariants that fall out
- **Consented creation:** the CLI only ever creates a state dir via a skill handshake
  with a fresh user "yes" — two paths only: clean first run, or config-present-but-dir-
  missing.
- **No migration, ever:** the CLI never moves/copies/transforms state. Anything it
  can't unambiguously honor → hard fail.
- **Config is the source of truth** for "this repo uses external state": no deterministic
  hash-derived paths; external paths are recorded and inspectable; the handshake proposes
  a path the user accepts or overrides.

### First-run handshake presentation
- On a clean first run, both options are offered in one step; **external (at the proposed
  path) is marked recommended/default**, in-repo is one step away. Expresses
  "non-intrusive by default" without hiding the in-repo choice.

## Open design questions (resolved as tasks below)
- What, if anything, to do about possible existing users/deployments.
- Whether/how to tag a folder as shipsmooth-owned in future (e.g. a manifest marker),
  which could later replace "is this `.shipsmooth/` really ours?" heuristics.
- Renaming the config file `ss-config.toml` → `shipsmooth.toml`.

## Tasks

> Risk levels below are **proposed defaults** — to be calibrated with the human in
> Phase 1 before re-ordering and `plan init`. Listed here in rough logical order, not
> yet risk-sorted.

### Task 1: Decide and implement policy for existing users / deployments [High]
Determine what (if anything) must be done for any existing users or deployments before
the `.agents/` → `.shipsmooth/` rename and external-by-default flip land, **and implement
the chosen policy in code.** Working assumption is "no external users yet," but this task
makes that an explicit, verified decision: confirm there are no deployments relying on
`.agents/`, and settle the stance (no back-compat, no migration). Then implement it — at
minimum, the CLI must **detect a legacy `.agents/` data tree and fail loudly** with an
actionable message (rename it to `.shipsmooth/` by hand) rather than silently treating
the repo as unconfigured and stranding the user's existing plan history. (This is the
deliberate, code-backed version of "no back-compat" — a guard, not a migration.) Record
the decision and any one-time manual steps in the plan.

### Task 2: Rename config file `ss-config.toml` → `shipsmooth.toml` [Low]
Rename the user config file from `~/.config/shipsmooth/ss-config.toml` to
`~/.config/shipsmooth/shipsmooth.toml`. Update `DefaultConfigFileLocator`, the error
message in `ProjectDataStore`, javadoc references, and any docs/SKILL prose. No
back-compat fallback to the old name (consistent with the no-`.agents/`-back-compat
stance).

### Task 3: Rename `.agents/` → `.shipsmooth/` and make `stateRoot` own the layout [Low]
*Depends-on: 2*
Change `ShipsmoothDataLocator` so the data-tree segment is `.shipsmooth` (in-repo) and
`plans/` hangs directly off `stateRoot` in standalone mode (drop the dot-folder segment
when `stateRoot` is a dedicated `stateDir`). Update the in-repo vs standalone wiring so
`stateRoot = repoRoot/.shipsmooth` for in-repo and `stateRoot = stateDir` for standalone.
Rename this repo's own `.agents/` tree (plans + every hardcoded test path, `.gitignore`,
and SKILL `.jte.md` references) so the build is green under the new name. This is the
load-bearing structural change.

### Task 4: Implement the branch-table resolution + needs-decision protocol [High]
*Depends-on: 3*
Rework `ProjectDataStoreResolver.resolve()` (and `ProjectDataStore`) to implement the
full branch table: silent resolution when settled, hard-fail on ambiguous/corrupt or a
config that points at a missing dir being treated per the table, config-wins precedence
when both in-repo and a configured state repo exist, and a structured *needs-decision +
options (with recommended marker)* return for the unsettled cases. The CLI must never
prompt on stdin. Define the machine-readable contract the skill consumes.

### Task 5: First-run handshake in the skill + config-writing path [High]
*Depends-on: 4*
Wire the skill side: when the CLI returns needs-decision, the skill presents the options
(external recommended, in-repo offered), gets a real user answer, and re-invokes the CLI
to (a) create the chosen state dir under consented-creation rules and (b) **write the
config entry** recording an external choice keyed on `(localPath, remoteUrl)`. Steady
state stays silent. Covers the clean-first-run and config-present-but-dir-missing flows.

### Task 6: Implement a "shipsmooth-owned folder" manifest marker [Medium]
*Depends-on: 4*
Tag a folder as shipsmooth data with a manifest marker file (e.g.
`.shipsmooth/shipsmooth.manifest`) written at creation time, so that "is this folder
really shipsmooth state?" becomes a fact, not a heuristic. The marker lets the CLI
distinguish a shipsmooth-created `.shipsmooth/` from a coincidentally-named dir, and
feeds the branch table's detection/precedence logic (e.g. an in-repo `.shipsmooth/`
without the marker is not treated as settled shipsmooth state). Decide the marker's
contents (at minimum a format/version stamp) and wire its creation into the
consented-creation paths and its detection into `resolve()`.

## Notes
- Per-assistant native opt-out (Claude/Codex/Gemini) is **explicitly out of scope** —
  with external-as-default, the only cross-host mechanism needed is the agent-agnostic
  config entry + the skill handshake, which work identically everywhere. The deferred/
  abandoned plan-84 per-host opt-in work is not revived here.
