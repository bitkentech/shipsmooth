# Plan 102 — Rust migration exploration (walking skeleton + XML round-trip spike)

## Context

`docs/rust-migration/` (committed on the `ss-rust` branch, 51f4946) holds the
full migration outline for the `core` and `cli` Gradle modules: overview and
risks (`00-overview.md`), per-package plans (`01-core.md`, `02-cli.md`), and
the Cargo dependency map (`03-dependencies.md`). This plan executes the
**starting sequence** from `00-overview.md` — the exploration slice only, not
the full migration.

The exploration exists to answer one go/no-go question early:

> Can a Rust implementation round-trip the `plan-{N}-tasks.xml` files written
> by the Java CLI — including `xs:any` extension elements (`<depends-on>`,
> unknown future fields) and JAXB-compatible formatting — cleanly enough that
> the rest of the migration is mechanical?

Everything else in the migration outline is downstream of that answer, which
is why this plan front-loads the XML model spike and defers the bulk ports
(gw, conf, cli commands) to a follow-up plan.

Feature rationale: groundwork for replacing the jlink-based Java runtime with
a single static Rust binary (kills the Semeru/shadow/module-info packaging
machinery; see `00-overview.md` §Verdict). No `<backlog-issue>` — local task
tracking only.

Rust code lives in a new top-level `rust/` directory (Cargo workspace with
`crates/ss-core` and `crates/ss-cli`), untouched by the Gradle build.

## Non-goals

- No port of `gw`, `conf`, or any cli package beyond what the spike needs —
  that is the follow-up plan, shaped by this one's findings.
- No changes to the Java modules, the Gradle build, or `packaging/`/`harness/`.
- No release-pipeline work (artifact shape, installers, SKILL cliBin paths).
- No CI wiring beyond what `cargo test` needs locally.

## Task ordering note

Tasks are risk-sorted per the skill (High first), with the spike's two hard
dependencies (fixtures, workspace) ahead of it. This deliberately diverges
from `00-overview.md` §Starting sequence, which puts the warm-up port before
the spike to establish conventions cheaply: in a risk-sorted plan the spike is
the whole point, so the warm-up (Task 4) follows it instead. If the spike
forces a hand-written event-API model, the warm-up conventions are unaffected.

## Tasks

### Task 1: Golden fixture corpus + CLI transcripts from the Java CLI [Low]

Generate and commit, under `rust/fixtures/`:

- A corpus of `plan-{N}-tasks.xml` files produced by the **Java** CLI
  (`$SS`/gradle-built), exercising every feature: multiple tasks, comments,
  deviations, `depends-on`, project updates, each status/risk enum value, and
  at least one file hand-extended with an *unknown* `xs:any` element to prove
  preservation of fields the model doesn't know.
- Recorded stdout + exit-code transcripts for representative subcommands
  (`plan resume`, `store info --json`, the resolution gate JSON on an
  unsettled dir) for the later parity harness.

Low risk: pure capture, no new code. Must land before the spike (hard
dependency) — these fixtures are its acceptance tests, produced while the
Java build is still the daily driver.

### Task 2: Cargo workspace walking skeleton [Low]

*Depends-on: 1*

`rust/` workspace per `03-dependencies.md`: `crates/ss-core` (lib) and
`crates/ss-cli` (bin `shipsmooth`), workspace-pinned deps, the `ss_core::Error`
thiserror enum, one placeholder test per crate, and a `rust/parity/` skeleton
script that can invoke both the Java CLI and the Rust binary on a fixture dir
(wired to real checks in later plans). `cargo test` green from the repo root.

Low risk: scaffolding with known-good crate choices. Hard dependency of the
spike (depends on Task 1 only for the parity-script fixture paths).

### Task 3: Risk spike — XML model + golden round-trip [High]

*Depends-on: 1, 2*

The go/no-go question. In `ss_core::model`: hand-written structs for
`plan-tasks.xsd` (per `01-core.md` §1), with `xs:any` extension elements
preserved as raw elements. Acceptance = the golden test: for every Task 1
fixture, read → write back → diff empty (or a single agreed, documented
normalization). Start with quick-xml + serde derive; fall back to the
quick-xml event API for the task/metadata elements if serde can't round-trip
`xs:any` faithfully — determining which of those two it must be **is the
spike's purpose**.

High risk: unproven library behaviour on the two hardest requirements
(unknown-element preservation, JAXB-compatible formatted output); the outcome
can restructure `01-core.md` §1.

### Task 4: Warm-up port — pure plan logic with ported tests [Low]

*Depends-on: 2*

Port `io.bitken.ss.svc.plan`'s pure classes to `ss_core::plan`: `Slugs`,
`PlanMarkdownParser`, `PlanNumbers`, `Stub`, `PlanSummaryFormatter` — with
`SlugsTest`, `PlanNumbersTest`, and the parser/formatter test cases ported
verbatim (expected strings are the spec). `PlanSummaryFormatter` binds to the
real Task 3 model if the spike has landed, else to a minimal stub struct
(per `01-core.md` §2).

Low risk: pure logic, behaviour pinned by ported tests. Purpose beyond the
port itself: establishes the crate conventions (error style, test layout,
verbatim format strings) the full migration will copy.

### Task 5: Findings write-back into docs/rust-migration [Low]

*Depends-on: 3, 4*

Update the migration docs with what the exploration proved: the go/no-go
verdict, the chosen XML approach (serde derive vs event API) and its
consequences for `01-core.md` §1, any dependency-map corrections
(`03-dependencies.md`), measured fixture round-trip caveats, and the concrete
scope recommendation for the follow-up plan (gw + conf next). Docs-only task;
it is the exploration's deliverable.

### Task 6: Dependency-complete binary footprint spike [Low]

*Depends-on: 2*

Answer the sizing question before the bulk port: with **every** runtime crate
from `03-dependencies.md` linked (serde, quick-xml, time, regex, thiserror,
clap, serde_json, toml_edit, unicode-normalization) and genuinely exercised
(so the linker cannot dead-strip them), what do the release binary's size and
memory footprint look like versus the Java jlink runtime?

Deliverables, written back into `03-dependencies.md`:

- Release binary size, default profile and a size-tuned profile
  (`opt-level="z"`, LTO, strip, `panic="abort"`).
- Peak RSS and wall time for representative invocations (`--version`, an XML
  round-trip over the golden corpus), measured with `/usr/bin/time -v`.
- The same measurements for the installed Java CLI (jlink image, 103 MB on
  disk) on equivalent commands, as the comparison baseline.

Low risk: additive dependency wiring + measurement; no behaviour changes to
ported code. (Added at plan v3 after the initial closeout; tasks 1–5 remain
closed.)
