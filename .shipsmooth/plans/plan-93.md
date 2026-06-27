# plan-93 — rename config/CLI terms: storageType + embedded/filesystem; storageRoot; --type not --choice

## Context

Backlog: **PB-365** — naming improvements for the standalone config and the `store init`
CLI, surfaced while reviewing plan-91 (TOML schema) output.

**Three renames, no back-compat** (revised at v4 — see below). The vocabulary was settled
during Task 1 around a **storage-type-as-backend** model, because "external" is destined to
generalise beyond a filesystem directory (a database, later perhaps a hosted service). The
axis is *which storage backend*, not *where on disk* — a discriminated union keyed on
`storageType`:

1. **Config key `mode` → `storageType`.** The discriminator naming which storage backend a
   project uses.
2. **Config values `in-repo`/`external` → `embedded`/`filesystem`.** Peer *backend* names,
   not location labels — so a future `database` backend slots in as a third value without
   re-working the pairing. `embedded` = state inside the repo's `.shipsmooth/`; `filesystem`
   = state in a separate directory.
3. **Config key `stateDir` → `storageRoot`.** The filesystem backend's location. Renamed
   because (a) `stateDir` clashed with the `storage*` vocabulary (`state` vs `storage`), and
   (b) it is **type-specific** — `storageRoot` belongs to the `filesystem` type; other types
   carry their own keys (e.g. a `database` type → a `storageUrl`, and *no* `storageRoot`).
   "Root" (not "Dir") is truthful: it is the root of the store's tree (`plans/`, task XML,
   the store's own git repo), and rhymes deliberately with the existing
   `stateRoot`/`ResolvedStateRoot` resolved-root vocabulary.
4. **CLI flag `store init --choice` → `--type`.** Terse `--type` (not `--storage-type`)
   because `store init` already supplies the "storage" context; `store init --type
   filesystem` reads cleanly where `--storage-type filesystem` stammers. Lands the standing
   `// TODO: rename --choice to --type` in `store/Init.java:46`.

### No back-compat (v4 revision)

**This tool has no users yet, so there are no on-disk `shipsmooth.toml` files in the old
vocabulary to preserve.** The original plan (v1–v3) treated back-compat reading of old
`mode`/`in-repo`/`external`/`stateDir` as the central hazard (a High-risk Task 2). That is
**dropped**: the reader is changed to read the new keys/values only. If an old-vocabulary
file were somehow encountered, it simply won't match (its keys are unknown) and resolution
falls through to the normal first-run handshake — no special handling, no migration, no error
path. This collapses the plan from "bigger than a sed-rename" back to a straightforward,
mechanical rename across the config model, writer, resolver, CLI, JSON, skill, and schema.

### The storage-type model (forward-looking)

`storageType` is a **discriminator**; the *other* keys in a `[[projects]]` block are
**type-specific**. `filesystem` carries `storageRoot`; a future `database` carries its own
key(s) and no `storageRoot`. Encoding: keep all type-specific keys `optional` at the schema
level (the TOML-schema spec can't express "required iff storageType == filesystem"
conditionals) and let the **resolver** enforce the per-type required/forbidden combination —
which it already does today for in-repo (no stateDir) vs external (stateDir required). No
schema rework is needed to add a backend later; just new optional keys + resolver validation.

### Full rename table

| Old (today) | New | Notes |
|---|---|---|
| `mode` (config key) | `storageType` | the backend discriminator |
| `in-repo` (value) | `embedded` | peer backend name |
| `external` (value) | `filesystem` | peer backend name |
| `stateDir` (config key) | `storageRoot` | type-specific to `filesystem`; future types carry their own keys |
| `--choice` (flag) | `--type` | flag in `store init`; context already implies "storage" |

### Confirmed touch-points (verified against current code)

- **Config model:** `StandaloneConfig.ProjectEntry` — Jackson fields `mode` (getter/setter
  L40/57) and `stateDir` (L39). Rename to `storageType` + `storageRoot` (both the Jackson key
  bindings and, for coherence, the accessors).
- **Writer/emitter:** `ConfigWriter` (`entry.setMode("in-repo")` L78, `entry.setMode("external")`
  L70, `entry.setStateDir(...)` L71), `ArrayOfTablesTomlEmitter` (`appendKey(sb, "mode", …)`
  L30, `appendKey(sb, "stateDir", …)` L29) — write the new keys/values.
- **Resolver:** `ProjectDataStoreResolver` (`MODE_IN_REPO = "in-repo"` L70, `MODE_EXTERNAL`
  L71, `entry.getStateDir()` checks L79/88/96) — match the NEW values only (`embedded` /
  `filesystem`) and read the storage root from `storageRoot`.
- **JSON + report:** `ResolutionJson.ready` (`kv("mode", mode)` L84) and the
  `needs-decision` `choice` token (`choiceToken` L91-97 emits `in-repo`/`external`/`recreate`);
  `StateReport.printReady` (`mode = … ? "in-repo" : "external"` L24, human text L32). The JSON
  field becomes `storageType` with values `embedded`/`filesystem`; the `choice` token emits
  the new values.
- **Schema:** `cli/src/main/resources/shipsmooth.tosd` — `[elements.projects.mode]` +
  `allowedvalues=["in-repo","external"]` (L31-33) and `[elements.projects.stateDir]` (L28-30).
  Becomes `storageType` (allowed `embedded`/`filesystem`) + `storageRoot`; all optional.
- **CLI:** `store/Init.java` — `--choice` option + `paramLabel("CHOICE")` + description
  `external | in-repo | recreate` (L47-48), error strings (L81, L103), `parseChoice` values
  `external`/`in-repo`/`recreate` (L147-152). Rename flag to `--type`, values to
  `embedded`/`filesystem`/`recreate`. The internal enum `DataStoreResolution.Choice` keeps
  its name (user-facing strings only).
- **Skill prose:** `first-run-handshake.jte.md` (four `store init --choice … --json`
  examples), `phase2-execute.jte.md` (reads the JSON `mode` field; `mode: in-repo` example),
  `commit-message-convention.jte.md` (`in-repo`/`external` mode references).

### Settled vocabulary in context — the user-facing explanation (surface #1)

The canonical two-paragraph explanation the rename targets:

> shipsmooth keeps your plan and task state in one of two **storage types**, chosen the first
> time you set up a project. With the **embedded** type, that state lives inside the project
> itself, in a tool-owned `.shipsmooth/` directory (plan narratives under `.shipsmooth/plans/`
> and the per-plan task files) — convenient when the plan/task history is meant to travel
> with the code. With the **filesystem** type, the state lives in a separate directory
> *outside* the project repo — by default a sibling folder named `<repo>-shipsmooth` — so the
> project repository stays completely untouched ("zero-trace"). Filesystem is the recommended
> default: the state directory is your own project content you can version and push
> independently, while your code repo carries no trace of the tooling. (`storageType` names
> the backend, so future types — e.g. a database — slot in alongside these.)
>
> The chosen storage type is recorded per-project in your user-level `shipsmooth.toml`, and
> is selected at setup through the `store init` CLI flag. Each project is a `[[projects]]`
> entry keyed on its local path (and remote URL, if any). An embedded project is written as
> `storageType = 'embedded'` with no storage root; a filesystem project as
> `storageType = 'filesystem'` plus `storageRoot = '<absolute path>'`. Keys other than
> `storageType` are type-specific — `storageRoot` belongs to the filesystem type. You don't
> normally hand-edit this file: it's written when you answer the first-run prompt, which the
> agent drives via `shipsmooth store init --type <embedded | filesystem | recreate>` (with
> `--path <dir>` to name or relocate a filesystem store). `shipsmooth store info` then reports
> the resolved storage type and where your plan files live.

### Out of scope

No behavioural change to resolution logic; no new store types *implemented* (the `database`
type is illustrative — the model just leaves the door open); **no back-compat reading and no
migration** (no users; see above). The JSON `stateRoot` field and the `ResolvedStateRoot`
token (the *resolved* root the tool reports) are a different concept from the `storageRoot`
config key and are deliberately left as-is — reconciling `state*`/`storage*` fully is a later
pass. `recreate` staying in the `--type` value list (it is an action, not a storage type) is
noted in Task 3 but not split out in this plan.

## Tasks

> Risk-sorted. v4 dropped the back-compat task (the former High-risk hazard) because the tool
> has no users — what remains is a mechanical rename, all Low/Medium. Task 1 (done) settled
> the vocabulary and surfaces. Task 2 renames the config keys/values across model + writer +
> resolver. Task 3 renames the `store init` flag. Task 4 aligns the JSON field + skill prose.
> Task 5 finalises schema + release notes.

### Task 1: Map all UX surfaces, decide vocabulary + implications [Medium]

**Status: DONE** — Settled the vocabulary (`storageType` / `embedded` / `filesystem` / `storageRoot` / `--type`),
the storage-type-as-backend model, the four user-facing surfaces, and the now-moot
back-compat decisions (superseded by the v4 no-back-compat call). Decision record captured in
the Context above and the rename table. No production code. Gated Tasks 2+.

### Task 2: Rename the config keys/values across model, writer, and resolver [Medium]

The config rename, read and write together (no back-compat split needed now). Update
`StandaloneConfig.ProjectEntry` so the canonical serialized fields are `storageType` and
`storageRoot`. `ConfigWriter` + `ArrayOfTablesTomlEmitter` write
`storageType = 'embedded' | 'filesystem'` and `storageRoot = '<path>'` (filesystem only).
`ProjectDataStoreResolver` matches the new values (`embedded` / `filesystem`) and reads the
root from `storageRoot`. Prove with a fixture: a `shipsmooth.toml` written in the NEW
vocabulary (an embedded entry and a filesystem entry) round-trips and resolves `settled`.

### Task 3: Rename the `store init` flag --choice → --type [Medium]

*Depends-on: 2*

`store/Init.java`: rename the option to `--type` (param label, description
`embedded | filesystem | recreate`, error messages, `matchedOption("type")`); accept
`embedded`/`filesystem` as values. Remove the standing TODO. The internal
`DataStoreResolution.Choice` enum keeps its name — only the user-facing flag and value
strings change. *Note:* `recreate` is an action, not a storage type, yet rides in the
`--type` value list; kept as-is here, flagged for a possible future split (a `--recreate`
flag or `store repair`).

### Task 4: Align the store-info JSON field + skill prose [Medium]

*Depends-on: 2,3*

Update `ResolutionJson.ready` (field → `storageType`, values `embedded`/`filesystem`), the
`needs-decision` `choice` token, and `StateReport.printReady` (JSON + human text), and every
skill reference in lockstep — `first-run-handshake.jte.md` (`store init --type …`),
`phase2-execute.jte.md` (the JSON field it reads), and `commit-message-convention.jte.md`.
Skill and CLI must change together so the rendered skill never calls a flag the CLI no longer
has.

### Task 5: Schema + release notes [Low]

*Depends-on: 4*

Update `shipsmooth.tosd`: `[elements.projects.storageType]` (allowed `embedded`/`filesystem`)
+ `[elements.projects.storageRoot]` (optional), dropping the old `mode`/`stateDir` elements.
Write a plain release-notes call-out documenting the new config vocabulary as the current
shape — **no breaking-change / migration framing** (no users). Confirm a full render + the
TOML schema conformance test pass.
