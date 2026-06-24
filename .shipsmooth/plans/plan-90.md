# plan-90 — TOML config output should be multi-line array-of-tables, not single-line

## Context

**Feature (user's words):** the toml output should be multi-line, not single-line.

**Symptom.** `~/.config/shipsmooth/shipsmooth.toml` is written with the entire
`projects` array — including every project's fields — collapsed onto one line:

```toml
projects = [{remoteUrl = 'git@github.com:pramodbiligiri/audio-gen.git', localPath = '/home/pramod/workspace/audio-gen', stateDir = '/home/pramod/workspace/audio-gen-shipsmooth', mode = 'external'}]
```

This is unreadable and ungreppable, and gets worse as more projects are added.

**Desired output** — idiomatic TOML array-of-tables, one `[[projects]]` block
per project, one key per line:

```toml
[[projects]]
remoteUrl = 'git@github.com:pramodbiligiri/audio-gen.git'
localPath = '/home/pramod/workspace/audio-gen'
stateDir = '/home/pramod/workspace/audio-gen-shipsmooth'
mode = 'external'

[[projects]]
remoteUrl = 'git@github.com:pramodbiligiri/ss-toml.git'
localPath = '/home/pramod/workspace/ss-toml'
mode = 'in-repo'
```

(In-repo entries carry no `stateDir`, so that key is simply omitted from the block.)

### Root cause

The config is serialized in `ConfigWriter.writeAtomically` via Jackson's
`TomlMapper.writeValue(...)` (`jackson-dataformat-toml` 2.17.2). Jackson's TOML
**generator** renders an array of objects as an inline array of inline tables on
a single line — it has **no** array-of-tables (`[[name]]`) emission. This is a
confirmed, by-design limitation:

- Verified against upstream test `TomlGeneratorTest.arrayMixed()` (3.x branch):
  a list of objects serializes as `abc = [1, {foo = 1, bar = 2}]` — inline, one line.
- Tracked upstream as
  [FasterXML/jackson-dataformats-text#254](https://github.com/FasterXML/jackson-dataformats-text/issues/254);
  the only attempt to fix it, [PR #670](https://github.com/FasterXML/jackson-dataformats-text/pull/670)
  (`rajulbhatnagar`, branch `feature/254-toml-roundtrip`), is **open and contested**
  — maintainers called it *"very intrusive … for a minor issue"* and *"a 900+ file
  PR is a no-go"*. It targets **3.x**; this repo is on the **2.x** line. Not merged,
  not released, not a viable dependency.

### Chosen approach — hand-rolled emitter on the WRITE path only

Keep Jackson for **reading**; replace only the **write** serialization with a
small hand-rolled TOML emitter that produces `[[projects]]` blocks. The
deserialization side needs **no change**:

- Jackson's TOML *parser* already understands `[[projects]]` array-of-tables
  (it is standard TOML; only the *writer* is limited). **Empirically verified**
  on 2.17.2: a hand-written 3-project `[[projects]]` file parses back into a
  3-element `projects` array with all fields mapped, and an in-repo entry with no
  `stateDir` comes back absent (not empty-string).
- `ProjectDataStoreResolver` (the resolve read path stabilized in plan-87 for the
  modular/jlink runtime) is therefore **untouched** — no JPMS/jlink risk.
- `ConfigWriter.readOrEmpty` (the read-modify-write upsert) keeps using Jackson
  to read, including reading back our own `[[projects]]` output.
- **Back-compat is free:** existing single-line `projects = [{...}]` files still
  parse on read; they are rewritten to `[[projects]]` form on the next upsert.

The round-trip becomes deliberately asymmetric:

| Path | Code | Format |
|---|---|---|
| Write | new hand-rolled emitter in `ConfigWriter` | emits `[[projects]]` |
| Read | unchanged Jackson `TomlMapper` | already parses `[[projects]]` |

### Design constraints to preserve

1. **Atomic write (plan-87).** The temp-file-then-atomic-move dance in
   `writeAtomically` must remain: a failed serialize must leave the existing
   config byte-for-byte intact and leave no `.tmp` litter. The new emitter must
   slot inside that same guard.
2. **String quoting/escaping.** Match Jackson's current style: single-quoted
   literal strings (`'...'`) for values with no single quote (true for all our
   `git@…` URLs and filesystem paths). Provide a correct fallback (double-quoted
   basic string with escaping) for any value containing a single quote, so the
   emitter is correct for arbitrary input, not just the happy path.
3. **Field order & omission.** Emit keys in a stable, readable order
   (`remoteUrl`, `localPath`, `stateDir`, `mode`); omit any key whose value is
   null/absent (notably `stateDir` for in-repo entries, `remoteUrl` when absent).
4. **Empty config.** Zero projects must produce a valid (empty or minimal) file
   that reads back as an empty `projects` list.
5. **Existing test impact.** `ConfigWriterTest.failedSerialize_…` injects an
   exploding `TomlMapper.writeValue` to simulate a serialize failure. Since the
   projects no longer route through `toml.writeValue`, that test must be updated
   to trigger the failure through the new emitter path while still asserting the
   atomic-write guarantees.

### Backlog / feature link

`[Local]` Local tracking mode. No external backlog issue; this plan file is the
feature record. (Topic also reflected in the working branch name `toml-fix`.)

## Tasks

> Risk levels are the agent's **default** estimates, pending human calibration.

### Task 1: Hand-rolled `[[projects]]` TOML emitter with round-trip + escaping coverage [High]

Introduce a small, self-contained emitter that serializes a `StandaloneConfig`
to array-of-tables TOML text, and route `ConfigWriter`'s write path through it
(replacing `toml.writeValue` for the projects array) **inside** the existing
`writeAtomically` temp-file/atomic-move guard.

- Add a unit test asserting the emitted text is the expected multi-line
  `[[projects]]` shape for: zero projects, one external project (with
  `stateDir`), one in-repo project (no `stateDir`), and multiple mixed projects.
- Add a round-trip test: emit → Jackson `TomlMapper` reads it back into an
  equivalent `StandaloneConfig` (this is the core de-risk — proves the asymmetric
  read/write contract holds end to end).
- Add escaping tests: a value containing a single quote falls back to a
  correctly-escaped basic string and round-trips; ordinary paths/URLs use literal
  `'...'` strings.
- Preserve key ordering (`remoteUrl`, `localPath`, `stateDir`, `mode`) and omit
  absent keys.

*Rationale (High):* this is the architectural core — a hand-written serializer
whose correctness (TOML quoting/escaping, round-trip fidelity) is the whole risk
of the plan. Everything else is low-risk once this is proven.

### Task 2: Preserve atomic-write guarantees through the new emitter [Medium]

*Depends-on: 1*

Ensure the plan-87 atomic-write contract still holds with the emitter in place,
and update the existing test that depended on `toml.writeValue`.

- Update `ConfigWriterTest.failedSerialize_leavesExistingConfigIntactAndNoTempLitter`
  so the injected failure now occurs on the emitter/write path (not the removed
  `toml.writeValue` route), still asserting: existing config survives
  byte-for-byte and no `.tmp` litter remains.
- Confirm the existing round-trip/idempotency/coexistence tests in
  `ConfigWriterTest` still pass unchanged against the new output (they read via
  the resolver, which is format-agnostic).

*Rationale (Medium):* mostly re-validation of an existing guarantee, but it
touches the failure-injection seam and must not regress the plan-87 fix.

### Task 3: Verify against the modular/jlink runtime and real upsert flow [Low]

*Depends-on: 1,2*

Confirm the change behaves in the packaged runtime, not just the classpath unit
tests (per the plan-87 lesson that classpath tests can miss JPMS issues — though
here the read path is unchanged, the write path must still run under the module).

- Run the jlink smoke / store init+info path (e.g. the `jlinkSmokeStore`-style
  check) and confirm `store init` writes a multi-line `[[projects]]` file and
  `store info` reads it back as `ready`.
- Manually confirm the produced `shipsmooth.toml` matches the desired shape and
  that adding a second project appends a second `[[projects]]` block (not a
  re-collapse to one line).

*Rationale (Low):* verification only; no new logic. The write path runs reflection-
free string output, so JPMS exposure is minimal, but worth confirming end to end.
