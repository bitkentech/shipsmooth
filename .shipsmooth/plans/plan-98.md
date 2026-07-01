# Plan 98 — A CLI-launched web UI (on a core-owned resolution policy)

## Context

**Feature (backlog):** [PB-359] *Explore moving state-store location/resolution
logic into core* — https://linear.app/pb-default/issue/PB-359 — and the roadmap item
*"Explore building a basic web UI"* it was raised in service of.

This plan began as an **exploration** and, after the Task 1 spike proved out, **pivoted
to delivery**: a shipped, CLI-launched web host (`shipsmooth web serve`). Its purpose is
to answer one question with running code — *can a non-CLI host (a browser UI) reuse
shipsmooth's data + resolution layer from `core` without re-implementing resolution
policy?* — and then package that host so it ships. PB-359 predicted that a Web UI would
be the first host to depend on `core` but not `cli`, and would otherwise be forced to
re-implement the branch table, config read/write, and first-run handshake. Plan-98 proves
that prediction by building the web host — after the policy has been lifted into `core`
so the web host can consume it — and ships it as a fast-jar payload launched by the CLI.

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
- **Shipped host (pivoted from throwaway):** the web module graduates from a spike to
  a shipped host, launched via the CLI as `shipsmooth web serve --port <p>`. The CLI is
  the single command surface; `web` becomes a noun group that launches the web
  presentation over the same `core` policy (the concrete payoff of PB-359's
  policy-in-core thesis). Still **no auth** — access control is out of scope for this
  plan. **Dependency direction is load-bearing:** `web` depends on `core`, never on
  `cli`; `cli` depends on `web` only to launch it. `web` must not grow a dependency back
  on `cli`, or the reuse proof collapses.
- **Packaging topology — separate fast-jar payload (Option B):** the web app ships as
  its own Quarkus fast-jar (`quarkus-app/`, ~17 MB, ~106 dep jars) placed **as a sibling
  to the CLI jlink image inside the same GitHub release zip**. `shipsmooth web serve`
  launches it as a child process. It is *not* embedded in the CLI jlink image — 106
  mostly-non-modular jars will not sit cleanly on a JPMS module path, and keeping the two
  runtimes decoupled contains failure (a broken/absent web payload still leaves `core`/`cli`
  shipping and running). GraalVM native-image (a lighter separate payload) is explicitly
  **deferred** — not this plan. Verified against the **claude devBuild** path.

### Non-goals

- No authentication / access control on the web host (deferred).
- No GraalVM native-image packaging (deferred — fast-jar payload only for now).
- No embedding of the web runtime *inside* the CLI jlink image — the web app is a
  separate fast-jar payload, not jlink modules (see packaging topology above).
- No new agent-harness host (this is not `harness:<name>` in the release sense — `web`
  is a CLI-launched host, not an agent-plugin host).
- No change to CLI behaviour or its `store info/init` JSON contract — the CLI keeps
  its non-interactive JSON+exit-code presentation; only the *policy* relocates. The new
  `web serve` verb is additive.
- No back-compat shims: there are no external consumers of `conf.ds.*`, so the lift
  is a straight move, not a deprecation.

### Open questions (to resolve during execution)

- **OQ-1 (web stack): RESOLVED → Quarkus.** Rationale: designed for performance and
  fast startup; strong developer experience; Kubernetes-native if we ever need it;
  actively developed with a large community; builds on Jakarta EE / Java EE standards;
  and pairs well with GraalVM native-image should that become useful (deferred).
  Chosen **over Micronaut** because Micronaut is oriented to the microservices use
  case and is Spring-derived — not compelling for shipsmooth today.
  *Correction (Task 1 finding):* an earlier rationale claimed Quarkus "works well with
  IBM Semeru." In fact dev compile/test/run resolves to **stock OpenJDK 25**, not
  Semeru — Semeru is jlink-only (see [[reference_semeru_jlink_only]]). Quarkus was
  validated on OpenJDK/HotSpot; Semeru/OpenJ9 validation is deferred. This does not
  change the stack choice.
- **OQ-2 (module placement): RESOLVED → top-level `web`.** Not `exp/` and not
  `harness:web`: `web` is a first-class CLI-launched host, so it sits as a top-level
  module alongside `core`/`cli`. (Confirmed in Task 1.)
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

### Task 8: `shipsmooth web serve` CLI subcommand [Medium]
*Depends-on: 6*

Add a `web` noun group with a `serve --port <p>` verb to the CLI command tree, so the
web host launches via the single `shipsmooth` command surface. The subcommand starts the
web app and blocks until interrupted — introducing shipsmooth's first *long-lived*
(daemon) process lifecycle, distinct from the tool's usual fire-and-exit model. In dev,
serve launches the web module directly; the packaged-launch form is Task 9. Keep the
`cli → web` dependency launch-only (no reach into web's presentation internals) and never
let `web` depend on `cli`. Medium: new command wiring + a process lifecycle the CLI has
never had, but bounded.

### Task 9: Package web fast-jar as CLI-sibling payload + verify on devBuild [High]
*Depends-on: 8*

Wire the Quarkus fast-jar (`quarkus-app/`) into the release zip **as a sibling to the
CLI jlink image** (Option B — a separate payload, NOT embedded in the jlink module path),
and make `shipsmooth web serve` launch that packaged payload as a child process. This is
the plan's real **deployment risk**: reconciling Quarkus's fast-jar packaging with
shipsmooth's jlink+zip assembly, version-aligning the two payloads, and resolving the
payload path at runtime from the installed layout. **De-risk first:** prove the fast-jar
lands in the assembled bundle and `web serve` boots it end-to-end via the **claude
devBuild** path before hardening. Success = a devBuild bundle contains the web payload
and `shipsmooth web serve --port <p>` serves the browser page from the *packaged* app
(not a `gradlew` invocation). High: touches release assembly, cross-runtime launch, and
installed-layout path resolution — the aspects most likely to break only at packaging time.

### Task 7: Findings write-up + PB-359 outcome [Low]
*Depends-on: 9*

Write a short `docs/observations/` note capturing what the plan proved: did `core` reuse
work cleanly (web depends on `core`, not `cli`), what the resolution API looks like
long-term, how the separate-payload packaging (Option B) held up, and the resolved
OQ-1/OQ-2/OQ-3 answers. Record the deferred items (auth, Semeru/OpenJ9 validation, GraalVM
native-image). Update PB-359 with the outcome. Low: documentation + capturing decisions.
