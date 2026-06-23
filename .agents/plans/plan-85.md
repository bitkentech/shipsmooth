# plan-85 — shipsmooth data folder naming & first-run UX

## Context

This plan redesigns **how shipsmooth names and locates its data tree** — both the
external folder (where standalone state lives) and the internal layout under it —
and defines the **first-run user experience** for choosing where state goes.

It is the concrete realisation of the long-standing *standalone-as-default*
direction: shipsmooth should be **non-intrusive by default**, not writing a folder
into the user's repo unless they opt into in-repo mode.

Backlog / feature link (local): **Feature — "Non-intrusive standalone-by-default data
storage."** Shipsmooth should not write a folder into the user's repo unless they opt into
in-repo mode; its data tree should be tool-owned (`.shipsmooth/`) and, by default, live
outside the project repo, with a first-run handshake to choose the location. This plan is
the concrete delivery of that feature and of the long-standing *standalone-as-default*
direction. (No external tracker; recorded here as the permanent feature reference per the
[Local]-mode convention.)

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

> Risk levels below were **calibrated with the human in Phase 1** (Task 3 bumped
> Low → Medium). Tasks are now **risk-sorted descending**, with dependencies overriding
> pure risk order where a lower-risk task is a hard prerequisite for a higher-risk one.
> **Task IDs are stable** (`### Task N:` — the CLI parses them as identifiers and
> `Depends-on` references them by number); physical order ≠ numeric order on purpose.
>
> Final risk sort & rationale (dependencies override pure risk order; the chain
> 2←3←4←{5,6,7,8,9,10,11,12} keeps execution close to numeric order):
> 1. **Task 1** (High, no deps) — foundational policy + legacy-`.agents/` fail-loud guard.
> 2. **Task 2** (Low) — hard prerequisite for Task 3.
> 3. **Task 3** (Medium) — load-bearing structural rename; prerequisite for Task 4.
> 4. **Task 4** (High) — core `resolve()` rewrite; prerequisite for Tasks 5–12.
> 5. **Task 5** (High) — first-run handshake + config write.
> 6. **Task 11** (High) — `Provider<T>` leaf wiring so the command tree builds when the
>    state root is unsettled (load-bearing for `--help`/comprehensive tree; depends on 4).
> 7. **Task 10** (Medium) — config can express in-repo explicitly (depends on 4; pairs with 5).
> 8. **Task 6** (Medium) — manifest marker.
> 9. **Task 7** (Medium) — external plan-context discoverability.
> 10. **Task 8** (Medium) — de-fingerprint project-repo commits.
> 11. **Task 12** (Medium) — `ResolvedStateRoot` token hardening of the locator (depends on 11;
>     rides the `Provider.get()` seam, removes redundant constructor I/O).
> 12. **Task 9** (Low) — explore + design only (no implementation) for task↔SHA storage.

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

### Task 3: Rename `.agents/` → `.shipsmooth/` and make `stateRoot` own the layout [Medium]
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
config entry** recording the choice keyed on `(localPath, remoteUrl)`. Steady state stays
silent. Covers the clean-first-run and config-present-but-dir-missing flows. (The in-repo
choice is persisted via the explicit in-repo config entry from **Task 10**, so a chosen
in-repo project is not re-prompted on every run.)

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

### Task 7: Point the agent at the external state path for plan context [Medium]
*Depends-on: 4*
External-by-default moves plan narratives (`plan-N.md`) out of the project repo's git
tree. They are **not lost** — the standalone state dir is itself a git repo and the files
are Readable on the same filesystem — but a coding agent won't *think to look there*
unless told. Close that discoverability gap: when the CLI resolves an **external** state
root, it reports that path back to the skill (it already knows it — it's the brain), and
the skill explicitly directs the agent to read plan context from `<stateRoot>/plans/`.
Goal: an agent picking up work in external mode reliably loads the plan narrative for
context, the same as it would for in-repo plans. Skill-prose + CLI-output concern; no new
detection logic (rides the needs-decision/resolution contract from Task 4).

### Task 8: Strip plan/task fingerprints from project-repo commits in standalone mode [Medium]
*Depends-on: 4*
In standalone (zero-trace) mode the user's **project repo** must stay free of shipsmooth
fingerprints — not just the files, but the git history. Code commits the agent makes in
the project repo (today `task(N): ...` and `draft(N): de-risk ...`) must use plain,
feature-oriented messages with **no `plan(N)`/`task(N)` prefixes** and no plan references.
Task↔commit traceability is preserved where it belongs — the **state repo's** task XML,
which already records the commit hash via `set-commit` — and state-repo commits (plan
files, task XML) keep full plan/task info since that history is shipsmooth's own and
invisible to the user. Note the `plan(N): ...` commit is naturally a state-repo commit in
standalone mode (the plan file lives there), so only the code commits need de-fingerprint-
ing. Primarily a SKILL-prose change: the skill writes commit messages conditioned on the
mode the CLI resolved. In-repo mode keeps the existing prefixed convention unchanged.

### Task 9: Explore + design storing task commit SHAs in the state repo [Low]
*Depends-on: 4*
**Scope: exploration and design only — no implementation in this task.** In standalone
mode the project-repo commits are de-fingerprinted (Task 8), so the **only** durable link
between a task and the code commit that delivered it is whatever the state repo records.
Explore the options and produce a design recommendation (written into the plan/notes) for
whether — and how — task-related commit SHAs should be stored in the state repo. The task
XML already has a `set-commit`/`<commit>` field per task; assess whether that is sufficient
as the canonical task↔SHA index, or whether more is needed (e.g. capturing draft/de-risk
commit SHAs too, or a separate ledger), so that a human or agent could later reconstruct
"which commit(s) delivered task N" purely from the state repo — even after the project-repo
log has been de-fingerprinted. Deliverable is the analysis + recommended shape; actual
implementation, if any, is deferred to a follow-up task/plan.

### Task 10: Let `shipsmooth.toml` express in-repo mode explicitly [Medium]
*Depends-on: 4*
Today the config schema can only record an **external** choice (a `stateDir` per project
entry); in-repo mode is purely the *negative* — inferred when no entry matches — and a
matched entry without a `stateDir` is a hard error. With external-as-default, **in-repo
becomes the opt-in choice**, so the config must be able to record it too. Otherwise the
only durable record of an in-repo decision is the on-disk `.shipsmooth/` folder, and the
handshake (Task 5) has nowhere to persist a user's "use in-repo" answer — risking a
re-prompt every run.

Make **config the single source of truth for what the user picked**: extend the schema so
a project entry can declare in-repo mode (e.g. a `mode = "in-repo"` field, with `stateDir`
optional/ignored for that mode; an entry must specify exactly one of in-repo vs an external
`stateDir`). Update `ProjectDataStoreResolver` so a matched in-repo entry resolves to
`ProjectDataStore.InRepo` (rather than only the no-match fallthrough), and update Task 5's
handshake to **write an in-repo entry** when the user chooses in-repo. Folder presence
remains corroborating (Task 6 marker), not the authoritative record. Settle precedence
when a config entry and on-disk state disagree, consistent with the Task 4 branch table
("config wins"). Keep validation loud: an entry that declares neither a valid mode nor a
`stateDir` is still a hard error.
- External mode does **not** mean the agent loses plan context: the state dir is a git
  repo on the same filesystem, Readable via the CLI-resolved path. The only real loss vs.
  in-repo is *same-repo co-evolution / travels-with-clone*; Task 7 closes the
  discoverability gap so the agent reliably reads plan narratives wherever they live.
- Per-assistant native opt-out (Claude/Codex/Gemini) is **explicitly out of scope** —
  with external-as-default, the only cross-host mechanism needed is the agent-agnostic
  config entry + the skill handshake, which work identically everywhere. The deferred/
  abandoned plan-84 per-host opt-in work is not revived here.

### Task 11: Convert command leaves to `Provider<T>` so the tree builds when unsettled [High]
*Depends-on: 4*
External-by-default + the first-run handshake create a legitimate, common state — a clean
first run — where **no valid state root exists yet**. Today commands hold their
state-dependent collaborators (e.g. `TaskStore`) directly, built at construction time, so
the *whole command tree cannot be constructed* without a settled state root. That blocks
showing a comprehensive `--help`/command tree (and any state-independent command) on an
unsettled project. Fix the **constructability** axis: change the affected command ctors to
inject `Provider<TaskStore>` (Dagger `javax.inject.Provider`/`Lazy`) instead of the built
collaborator, and pull `.get()` only inside `call()`. Group-parents that build their own
leaves (`plan`/`task`, per the CLI conventions) must pass providers down rather than built
collaborators. Net effect: every command **constructs** with no state, so the tree is
state-independent; the first touch of state moves to `Provider.get()` inside `call()`,
where the existing throwing `provideStateRoot()` still emits the needs-decision JSON +
exit code (the resolve-gate from Task 4) for genuinely state-dependent commands. This is
the load-bearing fix for the comprehensive tree; Task 12 hardens the seam it creates.
Scope is mechanical but listable — enumerate the command classes that inject a
state-dependent collaborator and convert each (plus the group-parent wiring). Note this is
orthogonal to Task 12: Provider alone (with today's throwing provider) already yields the
tree; the token is a separate refinement that rides the same `get()` seam.

**Concrete edge:** the partiality lives in `core/.../conf/ServicesModule.java`. It already
provides `@RepoRoot Path` (total), `@StateRoot Path`, and an `@Singleton`
`ShipsmoothDataLocator` derived from both — that three-way provision is the correct shape
and stays. The problem is `provideStateRoot()` does `stateRoot.orElseThrow(...)` (the
`unsettled(...)` path holds `Optional.empty()`), and `provideDataLocator` injects
`@StateRoot Path`, so the locator transitively inherits that partiality — and because
`TaskStore`/`PlanNumbers`/`NewPlan`/`PlanService` are eager `@Singleton`s that pull the
locator, the whole graph forces `provideStateRoot()` at construction. The conversion is to
make those eager consumers (and the command leaves) take `Provider<…>`/`Lazy<…>` so the
`orElseThrow` only fires on `.get()` inside `call()`; the module keeps providing all three
unchanged.

### Task 12: Introduce a `ResolvedStateRoot` token consumed by `ShipsmoothDataLocator` [Medium]
*Depends-on: 11*
Harden the **soundness** axis exposed by Task 11's seam. Today `ShipsmoothDataLocator`'s
constructor re-validates the state root with `Files.exists` — a side-effecting,
TOCTOU-prone re-derivation of a guarantee the resolver already established when it returned
a *settled* `DataStoreResolution` (Task 4). Apply **parse-don't-validate**: introduce a
small capability/token type — `ResolvedStateRoot` — that can **only** be minted by the
resolver's settled branch, and change the locator signature to
`ShipsmoothDataLocator(RepoRoot, ResolvedStateRoot)` so the unsettled case becomes
**unrepresentable** rather than guarded by a runtime check. `repoRoot` stays eager (it
always exists); only the state root carries the "not yet" state, now encoded as the absence
of a token. This removes the constructor's redundant `Files.exists` I/O, makes "no usable
state" a value carried by the type rather than a thrown exception, and leaves the resolver
as the single place partiality lives. The token is demanded at the `Provider.get()` seam
Task 11 creates: `get()` mints/consumes the token and, when there is none, fails into the
existing gate (needs-decision JSON + exit code). **Independence note:** this is a clean
follow-on, not a second migration through the call sites — once the Provider seam exists,
swapping the throw for a token demand is isolated to `provideStateRoot()`/the locator and
touches no command. If sizing proves larger than expected, it can ship after Task 11
without blocking the rest of the plan.

**Concrete edge:** same site as Task 11 — `ServicesModule.provideStateRoot()` and
`provideDataLocator(@RepoRoot Path, @StateRoot Path)`. Change `provideStateRoot` to mint a
`ResolvedStateRoot` from the settled branch (with `Optional.empty()` meaning "no token,"
which becomes the single throw-or-gate site), and change `provideDataLocator` to take the
token instead of a bare `@StateRoot Path`. `repoRoot` stays a total `@RepoRoot Path`. Once
the state-root binding is a distinct type, the `@StateRoot` qualifier is no longer
load-bearing for disambiguation (the two providers no longer collide on `Path`) — keep it
for symmetry or drop it, implementer's call.
