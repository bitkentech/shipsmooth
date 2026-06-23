# Plan 87 — Fix `store init`/`store info` JPMS-opens defect (`conf.ds` not opened to Jackson)

## Context

### The feature this serves
Backlog feature: **first-run state setup must work in the shipped runtime.** This is
the plan-85 "store info / init + first-run handshake" feature (see plan-85, PB-360/PB-361)
— it is currently **broken in the released 0.3.26 CLI** for any clean first run. Tracked
in-prose here per this repo's Local-mode convention (the `<backlog-issue>` element is left
empty, as in plans 80–86).

### The defect
A clean first run (`/shipsmooth:start` on a repo with no `.shipsmooth/` state) fails:

1. The first-run handshake offers external (recommended) vs in-repo. On accepting either,
   the CLI runs `store init`, which calls `ConfigWriter.upsert` →
   `TomlMapper.writeValue(configFile, StandaloneConfig)`.
2. In the **shipped jlink runtime** the CLI runs as a real JPMS module. Jackson must reflect
   over `io.bitken.ss.cli.conf.ds.StandaloneConfig` / `StandaloneConfig.ProjectEntry` to
   serialize them — but `cli/src/main/java/module-info.java` **never opens
   `io.bitken.ss.cli.conf.ds` to `com.fasterxml.jackson.databind`**. The write throws an
   `InaccessibleObjectException`.
3. Jackson, while failing the write, still creates and truncates the target file → a
   **0-byte `~/.config/shipsmooth/shipsmooth.toml`** is left behind.
4. From then on, **every** subsequent resolve (`store info`, `plan …`, all state-dependent
   commands) reads that empty file. `ProjectDataStoreResolver.parseConfig` →
   `TomlMapper.readValue` on a 0-byte / content-only file fails too, and the resolver maps
   the failure to `Unresolvable(UNKNOWN)`. The whole CLI is now wedged — even repos that
   already have a valid in-repo `.shipsmooth/plans/` resolve to UNKNOWN, because the bad
   global config file poisons resolution for every project.

### Why existing tests did not catch it
`ConfigWriterTest` and `InitTest` exercise the **real** `ConfigWriter`/`TomlMapper` and are
green — because unit tests run on the **classpath**, where JPMS `opens` rules are not
enforced. The bug only manifests when the CLI runs as a genuine module (the jlink'd runtime
launched via the SCC launcher). The repo already has a modular smoke-test harness
(`jlinkSmokeHelp`, `jlinkSmokeShow` in `cli/build.gradle.kts`) that runs the real runtime;
this is the layer where a regression test belongs.

### Two linked fixes
- **Root cause:** open `io.bitken.ss.cli.conf.ds` to `com.fasterxml.jackson.databind` (and
  `…datatype.jsr310` if needed) in `module-info.java`. This makes serialize **and**
  deserialize work in the modular runtime.
- **Hardening:** make `ProjectDataStoreResolver` treat an unparseable / empty config file as
  *no usable config* — fall through to filesystem resolution — instead of `UNKNOWN`. A stray
  or truncated global config file must never be able to wedge an otherwise-valid project.
  (And `ConfigWriter` should not leave a truncated file behind on a failed write — write
  atomically via a temp file + move.)

### Scope boundary
This plan does **not** redesign the config format or the first-run handshake. It restores
the shipped runtime to the behaviour plan-85 already specified, plus defensive tolerance so
one bad write cannot cascade. No new version is released here — a human cuts the release.

## Tasks

### Task 1: Modular regression test for `store init` → `store info` round-trip [High]

Add an integration test that runs the **real modular runtime** (SCC launcher / jlink image,
as `jlinkSmoke*` do) through a clean first-run `store init --choice external` into a temp
state dir, then `store info --json`, asserting `status:"ready"`. This test must **fail (red)
on `main`'s current `module-info.java`** with the `InaccessibleObjectException`/UNKNOWN
symptom — proving it reproduces the shipped defect that classpath unit tests miss. This is
the contract for the whole plan; it gates Tasks 2–3.

This is highest-risk: it must run a `store init` against an isolated `XDG_CONFIG_HOME` +
temp repo through the modular launcher without touching the developer's real
`~/.config/shipsmooth`. Proving that harness works is the de-risk.

### Task 2: Open `conf.ds` to Jackson in `module-info.java` [High]

*Depends-on: 1*

Add `opens io.bitken.ss.cli.conf.ds to com.fasterxml.jackson.databind;` (plus jsr310 if the
red test shows it is needed) to `cli/src/main/java/module-info.java`. Turns Task 1's modular
test green: `store init` serialize and `store info` deserialize both succeed in the runtime.
No other behaviour changes. Highest-risk because it is the root-cause fix the plan exists to
deliver — everything else is defence in depth.

### Task 3: Harden the resolver + writer against a bad config file [Medium]

*Depends-on: 1*

Two defensive changes so a single failed/garbage write can never wedge resolution again:

- `ProjectDataStoreResolver`: an empty or unparseable config file resolves as **no config**
  (fall through to `fromFilesystem`), not `Unresolvable(UNKNOWN)`. A repo with a valid
  in-repo `.shipsmooth/plans/` must stay `ready` even if the global config file is empty or
  corrupt.
- `ConfigWriter`: write the config **atomically** (temp file + atomic move) so a failed
  serialize never leaves a truncated 0-byte file behind.

Unit-testable on the classpath (empty file, garbage bytes, valid-but-unrelated entry; and a
write that survives an injected serialize failure). Medium risk: contained, well-understood
file-handling logic.
