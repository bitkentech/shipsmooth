# plan-93 — rename config/CLI terms: storageType + embedded/filesystem; storageRoot; --type not --choice

## Context

Backlog: **PB-365** — naming improvements for the standalone config and the `store init`
CLI, surfaced while reviewing plan-91 (TOML schema) output.

**Three renames, one breaking concern.** The vocabulary was revised during Task 1 (see the
decision record) away from the originally-proposed `stateStoreType`/`local` toward a
**storage-type-as-backend** model, because "external" is destined to generalise beyond a
filesystem directory (a database, later perhaps a hosted service). The axis is therefore not
*where on disk* but *which storage backend* — a discriminated union keyed on `storageType`:

1. **Config key `mode` → `storageType`.** The discriminator naming which storage backend a
   project uses.
2. **Config values `in-repo`/`external` → `embedded`/`filesystem`.** Peer *backend* names,
   not location labels — so a future `database` backend slots in as a third value without
   re-working the pairing. `embedded` = state inside the repo's `.shipsmooth/`; `filesystem`
   = state in a separate directory.
3. **Config key `stateDir` → `storageRoot`.** The filesystem backend's location. Renamed
   for two reasons: (a) `stateDir` clashed with the `storage*` vocabulary (`state` vs
   `storage` in the same block); (b) it is **type-specific** — `storageRoot` belongs to the
   `filesystem` type; other types carry their own keys (e.g. a `database` type → a
   `storageUrl`, and *no* `storageRoot`). "Root" (not "Dir") is truthful: it is the root of
   the store's tree (`plans/`, task XML, the store's own git repo), and it rhymes
   deliberately with the existing `stateRoot`/`ResolvedStateRoot` resolved-root vocabulary.
4. **CLI flag `store init --choice` → `--type`.** Terse `--type` (not `--storage-type`)
   because `store init` already supplies the "storage" context; `store init --type
   filesystem` reads cleanly where `--storage-type filesystem` stammers. Lands the standing
   `// TODO: rename --choice to --type` in `store/Init.java:46`.

### The storage-type model (forward-looking)

`storageType` is a **discriminator**; the *other* keys in a `[[projects]]` block are
**type-specific**. `filesystem` carries `storageRoot`; a future `database` carries its own
key(s) and no `storageRoot`. Encoding: keep all type-specific keys `optional` at the schema
level (the TOML-schema spec can't express "required iff storageType == filesystem"
conditionals) and let the **resolver** enforce the per-type required/forbidden combination —
which it already does today for in-repo (no stateDir) vs external (stateDir required). No
schema rework is needed to add a backend later; just new optional keys + resolver validation.

### Why this is bigger than a sed-rename

The renames are mechanical; the cost is **back-compat reading**. Existing `shipsmooth.toml`
files on every user's disk carry `mode = 'in-repo'|'external'` and `stateDir = '…'`. After
the rename the reader must still accept the old keys *and* old values, or those projects
silently fail to resolve (state goes "unsettled" and the first-run handshake fires
spuriously). This mirrors the `.agents → .shipsmooth` cutover (a deliberate breaking release
with hand-migration notes). Note this is now **three** old tokens to read, not one:
`mode`, the old values (`in-repo`/`external`), and `stateDir`.

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
  L40/57) and `stateDir` (L39, getter/setter). Add `storageType` + `storageRoot`; keep
  reading `mode`/`stateDir` (back-compat). Java method names *may* stay `getMode`/`getStateDir`
  internally — only the on-disk Jackson key bindings are user-facing — but renaming the
  accessors too keeps the model coherent.
- **Writer/emitter:** `ConfigWriter` (`entry.setMode("in-repo")` L78, `entry.setMode("external")`
  L70, `entry.setStateDir(...)` L71), `ArrayOfTablesTomlEmitter` (`appendKey(sb, "mode", …)`
  L30, `appendKey(sb, "stateDir", …)` L29) — write the NEW keys/values.
- **Resolver:** `ProjectDataStoreResolver` (`MODE_IN_REPO = "in-repo"` L70, `MODE_EXTERNAL`
  L71, `entry.getStateDir()` checks L79/88/96) — match BOTH old and new values, and read the
  storage-root from either `storageRoot` or `stateDir`, on read.
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

The canonical two-paragraph explanation the rename targets (current behaviour, new
vocabulary):

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
type is illustrative — the model just leaves the door open); no migration *tool* (back-compat
*reading*, not a rewrite command). The JSON `stateRoot` field and the `ResolvedStateRoot`
token (the *resolved* root the tool reports) are a different concept from the `storageRoot`
config key and are deliberately left as-is — reconciling `state*`/`storage*` fully is a later
pass. `recreate` staying in the `--type` value list (it is an action, not a storage type) is
noted in Task 4 but not split out in this plan.

## Tasks

> Risk-sorted with the UX-mapping task first by design (it gates the rest). Task 1 settled
> every surface, the vocabulary, and the storage-type model — so the renames land against a
> decided contract. Task 2 de-risks the real hazard (back-compat read of old configs, now
> three old tokens); Tasks 3–4 carry the renames through writer/CLI; Task 5 aligns the JSON
> field + skill prose; Task 6 finalises schema + release notes. Risk levels are calibrated.

### Task 1: Map all UX surfaces, decide vocabulary + implications [Medium]

Before any code: enumerate every surface the rename touches and **decide** each, producing
the decision record below. Inputs gathered (see Context): config keys/values, `--choice`
flag, the `store info --json` `mode` field the skill reads, schema, skill prose.

**Four user-facing surfaces, in decreasing order of importance:**

1. **User-facing explanations and docs** — the words a human reads about how storage works:
   release notes, `store info` human-text output, prose explaining the vocabulary. Coherent
   and correct here first; this is what users learn from. (Drafted in Context above.)
2. **Config file** (`shipsmooth.toml`) — the keys/values the user or agent reads/edits, plus
   the back-compat read of old files.
3. **Skill file** — rendered SKILL.md prose + example commands, and the JSON field /
   `needs-decision` token the skill consumes. *(Internal contract — moves in lockstep with
   the CLI in Task 5.)*
4. **CLI flag** — `store init --choice` → `--type`, its `paramLabel`, description, errors,
   accepted values.

**Decision record (settled — gates Tasks 2–6):**

- **Vocabulary → DECIDED (revised from the original `stateStoreType`/`local`):**
  `storageType` (key) with values `embedded` / `filesystem`; the filesystem location key is
  `storageRoot` (was `stateDir`); the flag is `--type`. Rationale: "external" must generalise
  to non-filesystem backends (DB, service), so the axis is *which backend*, not *where on
  disk*. `local`/`external` was rejected — `local` is already true of both modes (both are
  local dirs today) and would mislead once a remote DB exists. `embedded`/`filesystem` are
  peer backend names; `storageType` is the discriminator; future types add their own keys.
- **Storage-type model → DECIDED:** `storageType` discriminates; other keys are
  type-specific (`storageRoot` ⇒ filesystem; a future `database` ⇒ its own key, no
  `storageRoot`). Schema keeps type-specific keys optional; the resolver enforces per-type
  validity (as it already does for in-repo vs external).
- **JSON field → DECIDED: rename to `storageType` + `embedded`/`filesystem`.** One coherent
  vocabulary across config, CLI, JSON, docs. The `needs-decision` `choice` token and the
  `--type` flag value are a closed loop the skill round-trips, so the flag rename already
  moves the new values into the JSON; leaving `ready.mode` behind would emit mismatched
  vocabulary in one output. Both JSON strings (the `ready` storage-type field and the
  `needs-decision.choice` token) move together. No on-disk back-compat burden (JSON is
  regenerated each run); cost is only keeping skill + CLI in lockstep (Task 5), and they ship
  together.
- **Old-value alias → DECIDED: config-file read only, not the `--type` flag.** Back-compat
  reading accepts old `mode`/`in-repo`/`external`/`stateDir` in existing files. The new
  `--type` flag accepts only the new values (`embedded | filesystem | recreate`). The flag is
  newly renamed, so no user depends on the old strings there.
- **Back-compat horizon → DECIDED: read the old form indefinitely.** New writes use the new
  vocabulary; files silently upgrade on next write. No future breaking drop planned — mirrors
  the `.agents → .shipsmooth` forgiving-read posture.
- **Migration posture → CONFIRMED: silent upgrade-on-next-write only.** No migration command.
- **`stateRoot` (JSON) / `ResolvedStateRoot` → DECIDED out of scope.** The *resolved* root the
  tool reports is a distinct concept from the `storageRoot` config key; left as-is this plan.

Output is the decision record above; no production code. Gates Tasks 2–6.

### Task 2: Back-compat read path for old config (mode/in-repo/external/stateDir) [High]

*Depends-on: 1*

Teach the read side to accept BOTH the old (`mode` / `in-repo` / `external` / `stateDir`) and
new (`storageType` / `embedded` / `filesystem` / `storageRoot`) keys+values before anything
starts writing the new form. `StandaloneConfig.ProjectEntry` reads either key into one
logical accessor for each of (a) the storage-type discriminator and (b) the storage-root
path; `ProjectDataStoreResolver` matches `in-repo`/`embedded` and `external`/`filesystem`,
and reads the root from `storageRoot` OR `stateDir`. Prove with a fixture: an existing
`shipsmooth.toml` written in the OLD vocabulary (both an in-repo and an external entry) still
resolves `settled` — no spurious first-run handshake. This is the hazard — get it right and
the renames become mechanical.

### Task 3: Rename the config keys/values on the write side [Medium]

*Depends-on: 2*

`ConfigWriter` + `ArrayOfTablesTomlEmitter` write `storageType = 'embedded' | 'filesystem'`
and `storageRoot = '<path>'` (filesystem only). New writes use the new vocabulary; the
back-compat reader (Task 2) means old files keep working and silently upgrade on next write.
Update `StandaloneConfig.ProjectEntry` so the canonical serialized fields are `storageType`
and `storageRoot`.

### Task 4: Rename the `store init` flag --choice → --type [Medium]

*Depends-on: 2*

`store/Init.java`: rename the option to `--type` (param label, description
`embedded | filesystem | recreate`, error messages, `matchedOption("type")`); accept
`embedded`/`filesystem` as values (new values only — no old-string alias on the flag, per
Task 1). Remove the standing TODO. The internal `DataStoreResolution.Choice` enum keeps its
name — only the user-facing flag and value strings change. *Note:* `recreate` is an action,
not a storage type, yet rides in the `--type` value list; kept as-is here, flagged for a
possible future split (a `--recreate` flag or `store repair`).

### Task 5: Align the store-info JSON field + skill prose [Medium]

*Depends-on: 3,4*

Per Task 1: update `ResolutionJson.ready` (field → `storageType`, values
`embedded`/`filesystem`), the `needs-decision` `choice` token, and `StateReport.printReady`
(JSON + human text), and every skill reference in lockstep — `first-run-handshake.jte.md`
(`store init --type …`), `phase2-execute.jte.md` (the JSON field it reads), and
`commit-message-convention.jte.md`. Skill and CLI must change together so the rendered skill
never calls a flag the CLI no longer has.

### Task 6: Schema + release notes [Low]

*Depends-on: 5*

Update `shipsmooth.tosd`: `[elements.projects.storageType]` (allowed `embedded`/`filesystem`)
+ `[elements.projects.storageRoot]` (optional), dropping the old `mode`/`stateDir` elements.
Write a release-notes call-out documenting the breaking config rename and the back-compat
read path (model it on the `.agents → .shipsmooth` cutover note; list all three renamed
tokens). Confirm a full render + the TOML schema conformance test pass.
