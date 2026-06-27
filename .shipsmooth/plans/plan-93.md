# plan-93 — rename config/CLI terms: stateStoreType + local/external; --type not --choice

## Context

Backlog: **PB-365** — naming improvements for the standalone config and the `store init`
CLI, surfaced while reviewing plan-91 (TOML schema) output.

Two renames, one breaking concern:

1. **Config key/value:** `mode` → `stateStoreType`; the `in-repo` *value* → `local`
   (pairs cleanly as `local` vs `external`; the current `in-repo`/`external` pairing is
   lopsided). So `[[projects]]` entries become `stateStoreType = 'local' | 'external'`.
2. **CLI flag:** `store init --choice <…>` → `store init --type <…>`. There is already a
   `// TODO: rename --choice to --type` in `store/Init.java:46`.

### Why this is bigger than a sed-rename

The renames are trivial; the cost is **back-compat**. Existing `shipsmooth.toml` files on
every user's disk carry `mode = 'in-repo'`. After the rename the reader must still accept
the old key *and* the old value, or those projects silently fail to resolve (state goes
"unsettled" and the first-run handshake fires spuriously). This mirrors the
`.agents → .shipsmooth` cutover (a deliberate breaking release with hand-migration notes).

### Confirmed touch-points (verified against current code)

- **Config model:** `StandaloneConfig.Project` — Jackson field `mode` (getter/setter at
  L40/57). Add `stateStoreType` + keep reading `mode` (back-compat).
- **Writer/emitter:** `ConfigWriter` (`entry.setMode("in-repo")` L78), `ArrayOfTablesTomlEmitter`
  (`appendKey(sb, "mode", …)` L30) — write the NEW key/value.
- **Resolver:** `ProjectDataStoreResolver` (`MODE_IN_REPO = "in-repo"` L70) — match both old
  and new values on read.
- **Schema:** `cli/src/main/resources/shipsmooth.tosd` — allowed key + values.
- **CLI:** `store/Init.java` — `--choice` option (L47), param label, error strings, the
  `parseChoice` value `"in-repo"` (L149). Rename flag to `--type`; the internal enum
  `DataStoreResolution.Choice` can keep its name (user-facing string only).
- **Skill prose:** `first-run-handshake.jte.md` (four `store init --choice … --json`
  examples incl. `--choice in-repo`), `phase2-execute.jte.md`, `commit-message-convention.jte.md`
  (`in-repo` mode references).

### Open decision — the `store info --json` `mode` field

Separate from the config key, `store info`/`store init` emit a JSON **`mode`** field
(`{"mode":"in-repo"|"external",…}` — `ResolutionJson.ready`, `StateReport`), and the
**start SKILL reads it** (`phase2-execute.jte.md`: "`mode: in-repo` simply reports …").
PB-365 does NOT mention this field. The plan must decide: rename the JSON field too
(`stateStoreType` + `local`, fully consistent, but a skill↔CLI contract change that must
move in lockstep) or leave the JSON `mode` as-is (smaller blast radius, but config and
JSON then disagree on terminology). **Recommended: rename it too** for one coherent
vocabulary — the skill and CLI ship together, so the contract moves atomically; but the
JSON has no on-disk back-compat burden (it's regenerated each run), so the cost is only
keeping skill + CLI in sync in this same change.

### Out of scope

No behavioural change to resolution logic, no new store types, no migration *tool* (we do
back-compat *reading*, not a rewrite-everything migration command). Writing the new form
on the next config write is sufficient to migrate users forward silently.

## Tasks

> Risk-sorted with the UX-mapping task first by design (it gates the rest). Task 1 settles
> every surface and implication — including the open JSON-field decision — so the renames
> land against a decided contract, not a moving one. Task 2 de-risks the real hazard
> (back-compat read of old configs); Tasks 3–4 carry the renames through writer/CLI; Task 5
> aligns the JSON field + skill prose; Task 6 finalises schema + release notes. Risk levels
> are **proposed** — pending human calibration.

### Task 1: Map all UX surfaces, decide implications [Medium]

Before any code: enumerate every surface the rename touches and **decide** each, producing
a short decision record in this plan file. Inputs already gathered (see Context):
config key/value, `--choice` flag, the `store info --json` `mode` field that the skill
reads, schema, and skill prose.

**Evaluate and fix the four user-facing surfaces, in decreasing order of importance:**

1. **User-facing explanations and docs** — the words a human reads about how state works:
   release notes, `store info` human-text output, any prose that *explains* the vocabulary
   (`local`/`external`). The terminology must be coherent and correct here first; this is
   what users actually learn from.
2. **Config file** (`shipsmooth.toml` on disk) — the `mode` key and `in-repo`/`external`
   values the user (or their agent) reads and edits, plus the back-compat read of old files.
3. **Skill file** — the rendered SKILL.md prose and example commands the agent executes
   (`first-run-handshake`, `phase2-execute`, `commit-message-convention`), and the
   `store info --json` `mode` field / `needs-decision` `choice` token the skill consumes.
4. **CLI flag** — `store init --choice` → `--type`, its `paramLabel`, description, error
   strings, and accepted values.

**Decision record (settled — these gate Tasks 2–6):**

- **JSON `mode` field → DECIDED: rename to `stateStoreType` + `local`.** One coherent
  vocabulary across config, CLI, JSON, schema, docs. Cost is only keeping skill + CLI in
  lockstep (Task 5) — they ship together, so the contract moves atomically; the JSON has no
  on-disk back-compat burden (regenerated each run). Decisive factor: the `needs-decision`
  `choice` token and the `--type` flag value are a closed loop the skill round-trips, so the
  flag rename already moves a `local` string into the JSON; leaving `ready.mode` as `in-repo`
  would emit `mode:"in-repo"` next to `choice:"local"` in the same output — incoherent. Both
  JSON `local` strings (the `ready.mode` field and the `needs-decision.choice` token) move
  together.
- **Old-value alias → DECIDED: config-file read only, not the `--type` flag.** Back-compat
  reading accepts the old `in-repo` value in existing `shipsmooth.toml` files. The new
  `--type` flag accepts only the new values (`local | external | recreate`). The flag is
  newly renamed, so no user depends on `in-repo` there; keeping the CLI surface clean avoids
  carrying the old vocabulary forward where there is no back-compat obligation.
- **Back-compat horizon → DECIDED: read the old `mode`/`in-repo` form indefinitely.** New
  writes use `stateStoreType`/`local`; files silently upgrade on next write. No future
  breaking drop is planned — a forgiving read mirrors the `.agents → .shipsmooth` posture
  without stranding any user.
- **Migration posture → CONFIRMED: silent upgrade-on-next-write only.** No migration command
  or rewrite-everything tool; writing the new form on the next config write is sufficient to
  migrate users forward.
- **User-facing vocabulary → CONFIRMED: `local` / `external` is the final pairing** across
  CLI help, JSON, config, schema, and skill prose.

Output is the decision record above; no production code. Gates Tasks 2–6.

### Task 2: Back-compat read path for old config (mode/in-repo) [High]

*Depends-on: 1*

Teach the read side to accept BOTH the old (`mode` / `in-repo`) and new
(`stateStoreType` / `local`) key+value before anything starts writing the new form.
`StandaloneConfig.Project` reads either key into one logical accessor; `ProjectDataStoreResolver`
matches `in-repo` OR `local`. Prove with a fixture: an existing `shipsmooth.toml` written
in the OLD vocabulary still resolves `settled` (no spurious first-run handshake). This is
the hazard — get it right and the renames become mechanical.

### Task 3: Rename the config key/value on the write side [Medium]

*Depends-on: 2*

`ConfigWriter` + `ArrayOfTablesTomlEmitter` write `stateStoreType = 'local' | 'external'`.
New config writes use the new vocabulary; the back-compat reader (Task 2) means old files
keep working and silently upgrade on next write. Update `StandaloneConfig` so the canonical
serialized field is `stateStoreType`.

### Task 4: Rename the `store init` flag --choice → --type [Medium]

*Depends-on: 2*

`store/Init.java`: rename the option to `--type` (param label, description
`external | local | recreate`, error messages, `matchedOption`); accept `local` as the
value (alias handling per Task 1's decision). Remove the standing TODO. The internal
`DataStoreResolution.Choice` enum keeps its name — only the user-facing flag and value
strings change.

### Task 5: Align the store-info JSON field + skill prose [Medium]

*Depends-on: 3,4*

Per Task 1's decision on the JSON field: update `ResolutionJson.ready` / `StateReport` and
every skill reference in lockstep — `first-run-handshake.jte.md` (`store init --type …`),
`phase2-execute.jte.md`, `commit-message-convention.jte.md`. Skill and CLI must change
together so the rendered skill never calls a flag the CLI no longer has.

### Task 6: Schema + release notes [Low]

*Depends-on: 5*

Update `shipsmooth.tosd` allowed key/values (`stateStoreType`, `local`/`external`). Write a
release-notes call-out documenting the breaking config rename and the back-compat read path
(model it on the `.agents → .shipsmooth` cutover note). Confirm a full render + the TOML
schema conformance test pass.
