# Plan 98 — Explore a basic web UI (on a core-owned resolution policy)

## Context

**Feature (backlog):** [PB-359] *Explore moving state-store location/resolution
logic into core* — https://linear.app/pb-default/issue/PB-359 — and the roadmap item
*"Explore building a basic web UI"* it was raised in service of.

This plan is **exploratory**. Its purpose is to answer one question with running
code: *can a non-CLI host (a browser UI) reuse shipsmooth's data + resolution
layer from `core` without re-implementing resolution policy?* PB-359 predicted
that a Web UI would be the first host to depend on `core` but not `cli`, and would
otherwise be forced to re-implement the branch table, config read/write, and
first-run handshake. Plan-98 tests that prediction by actually building a thin web
host — but only after the policy has been lifted into `core` so the web host can
consume it.

### Current state (verified in-repo)

The entire resolution machine lives under `cli/src/main/java/io/bitken/ss/cli/conf/`:

- `conf/ds/ProjectDataStoreResolver` — the branch-table classifier (`resolve()`);
  detection only, never creates/prompts.
- `conf/ds/DataStoreResolution` — the sealed `Settled` / `NeedsDecision` /
  `Unresolvable` outcome type (+ `Option`, `Choice`, `UndecidableSituation`,
  `UnresolvableReason`).
- `conf/ds/ProjectDataStore` (`InRepo` / `Standalone`), `conf/ds/ConfigWriter`,
  `conf/ds/StandaloneConfig`, `conf/ds/LegacyDataTreeGuard`.
- `conf/ConfigFileLocator` + `conf/DefaultConfigFileLocator` (the `~/.config` path).

`core` already owns the *data* layer the resolver feeds — `ShipsmoothDataLocator`,
`ServicesModule`, `TaskStore`, `PlanService` — and, since plan-85 Task 12, the
`ResolvedStateRoot` capability token in `core/.../conf/`. That token is the clean
handoff contract PB-359 calls out: a `Settled` branch already produces a `core`
type.

**Blast radius of the lift (measured):**
- CLI consumers of the resolver are contained: `Shipsmooth`, `store/{Store,Info,Init}`,
  `ResolutionJson` (+ their tests). No other module references `conf.ds.*`.
- `core` has `jackson-databind` but **not** `jackson-dataformat-toml`; the resolver's
  TOML config read/write pulls `jackson-dataformat-toml` (and tests use `tomlj`).
  Moving the resolver down requires moving that dependency into `core`.
- `DefaultConfigFileLocator`'s `~/.config` path and the sealed type's package are
  the two things whose move is most visible to callers.

### Scope decisions (agreed at kickoff)

- **Web-UI ambition:** *Read + resolve UI.* The web host both (a) reads plan/task
  state and (b) drives an interactive first-run **resolution** flow in the browser —
  exercising `NeedsDecision` end-to-end. This is the presentation half PB-359 says
  each host should own; the *policy* it drives now lives in `core`.
- **Web stack: Quarkus** (decided at planning time — see OQ-1 rationale). Task 1
  remains the leading (High-risk) task, but its de-risk is now "prove Quarkus boots
  and serves a page in *this* repo/toolchain (Gradle + IBM Semeru)", not an open
  bake-off. This is still the plan's real spiral risk, so it leads.
- **Throwaway posture:** the web module is a spike. It is *not* a shipped host, has
  no auth, and is not wired into any release/packaging path. Deleting it must leave
  `core`/`cli` fully working.

### Non-goals

- No production web host, no authentication, no release/packaging/distribution wiring.
- No new agent-harness host (this is not `harness:<name>` in the release sense).
- No change to CLI behaviour or its `store info/init` JSON contract — the CLI keeps
  its non-interactive JSON+exit-code presentation; only the *policy* relocates.
- No back-compat shims: there are no external consumers of `conf.ds.*`, so the lift
  is a straight move, not a deprecation.

### Open questions (to resolve during execution)

- **OQ-1 (web stack): RESOLVED → Quarkus.** Rationale: designed for performance and
  fast startup; strong developer experience; works well with **IBM Semeru** (this
  repo's jlink JVM — see [[reference_semeru_jlink_only]]); Kubernetes-native if we
  ever need it; actively developed with a large community; builds on Jakarta EE / Java
  EE standards; and pairs well with GraalVM native-image should that become useful.
  Chosen **over Micronaut** because Micronaut is oriented to the microservices use
  case and is Spring-derived — not compelling for shipsmooth today. (Decision made at
  planning time, not deferred to Task 1's de-risk.)
- **OQ-2 (module placement):** where does the web spike module sit in the graph —
  `harness:web`, a top-level `web`, or `exp/`? Lean `exp/` given the throwaway
  posture; confirm in Task 1 when the module is created.
- **OQ-3 (lifted-policy home):** does the lifted resolution policy live directly in
  `core`, or in a new shared module (e.g. `core` stays framework-free and a
  `resolution` module sits above it)? Task 3 de-risk answers this; default is *into
  `core`* unless the TOML/Jackson dependency footprint argues for a separate module.

## Tasks

### Task 1: Spin up a web endpoint — decide the stack, serve a page [High]
*Depends-on:*

The leading spike: stand up a **Quarkus** web module (stack resolved — see OQ-1) and
serve a page in the browser, proving Quarkus integrates with *this* repo's toolchain
(Gradle build, IBM Semeru JVM, JPMS/module graph). Stand up the throwaway web module
to host it (OQ-2). No config, no `core` data, no resolution — just prove a server
boots and a browser can hit a page. High risk: this is the plan's real spiral risk —
Quarkus is chosen, but its integration with the Gradle + Semeru toolchain here is
unproven and gets validated by doing. Success = `./gradlew :<module>:run` (or the
Quarkus dev-mode equivalent) boots a server and a browser
loads a page from it.

### Task 2: Have the endpoint read the TOML config [Medium]
*Depends-on: 1*

Make the running page read and display the project's store config from the TOML
config file — the first real content the endpoint serves. At this task the read can
be direct (own `TomlMapper`), independent of where the resolver lives; wiring it to
the lifted policy comes later. Success = the page shows the resolved store config
(type/root) for the current project. Medium: straightforward config-reading over a
proven server.

### Task 3: Lift the resolution policy into core [Medium]
*Depends-on: 1*

Move the resolution *policy* — `ProjectDataStoreResolver`, `DataStoreResolution`
(+ `Option`/`Choice`/`UndecidableSituation`/`UnresolvableReason`), `ProjectDataStore`,
`ConfigWriter`, `StandaloneConfig`, `LegacyDataTreeGuard`, and the
`ConfigFileLocator`/`DefaultConfigFileLocator` pair — from `cli/.../conf/` down into
`core` (package `io.bitken.ss.conf...`; OQ-3), alongside the `ResolvedStateRoot` token
it already produces. Move `jackson-dataformat-toml` (and the `tomlj` test dep) into
`core`. Update the CLI consumers (`Shipsmooth`, `store/{Store,Info,Init}`,
`ResolutionJson`) to import from the new location. **De-risk first:** prove the package
move compiles and the existing resolver test suite passes from `core` before
hardening. Medium: mostly code movement, but it touches the module dependency graph,
TOML deps, JPMS `opens`/`exports` (cf. plan-87's `opens conf.ds to jackson.databind`
fix), and every resolver consumer.

### Task 4: core-side resolution API surface for non-CLI hosts [Medium]
*Depends-on: 3*

With the policy in `core`, expose a small, host-neutral entry point the web host can
call: resolve `(localPath, remoteUrl)` → `DataStoreResolution`, and act on a chosen
`Option` (the same `store init` semantics the CLI uses, minus CLI formatting). No
stdin, no `System.exit`, no JSON — those stay in the presentation layer. Prove it with
a `core` test that runs a clean-first-run → choose → settled cycle with a temp config
locator, independent of any `cli` class. Medium: mostly an extraction/adapter over
Task 3's move, but it defines the reuse contract the web host depends on.

### Task 5: Read-only plan/task view over core [Medium]
*Depends-on: 2,4*

Have the web module (which depends on `core` **but not `cli`**) serve a read-only view
of the current project's resolved plans/tasks via `PlanService`/`TaskStore` behind the
Task-4 API. Success = a browser page lists plans/tasks for a settled project, with
zero `cli` imports — the reuse proof PB-359 asked for. Medium: read-only and bounded,
over a proven server + API.

### Task 6: Interactive first-run resolution in the browser [Medium]
*Depends-on: 5*

Drive `NeedsDecision` end-to-end from the browser: on an unresolved project, render
the situation + options (recommended marked), take the user's choice, call the Task-4
act-on-choice API, and show the now-`Settled` state. This is the "resolve UI" half of
the agreed scope and the concrete demonstration that resolution *presentation* is
host-specific while *policy* is shared. Medium: exercises the full sealed-type surface
through a new presentation, over an API proven in Tasks 3–4.

### Task 7: Findings write-up + teardown decision [Low]
*Depends-on: 6*

Write a short `docs/observations/` note capturing what the spike proved: did `core`
reuse work cleanly, what the resolution API should look like long-term, whether the
web host warrants its own plan, and the resolved OQ-1/OQ-2/OQ-3 answers. Decide and
record whether the spike module is kept behind a flag or deleted (throwaway default).
Update PB-359 with the outcome. Low: documentation + a keep/delete call.
