# Package Split: `core` + Per-Client Packages

**Status:** Proposal
**Date:** 2026-05-27
**Subject:** Restructure `plugin-tasks-java` into a `core` package (domain + services) with sibling client packages (`cli`, `web`, future) instead of extracting separate Maven modules.

---

## 1. Context

`plugin-tasks-java` currently houses domain logic, services, and the CLI entrypoints in a single Maven module. A **web client is coming soon**, which raises the question of how to make the CLI one consumer among several without paying premature modularization tax.

The discussion considered three moves:

1. Break the codebase into three layers — domain/relational model, services, api/clients.
2. Rename `plugin-tasks-java` to `cli`.
3. Extract services into a separate Maven module so the CLI becomes one use case.

…and two design questions:

- Should the domain model and services live in the same codebase?
- Does breaking code into layered modules become an anti-pattern, given a general preference to avoid layered approaches?

---

## 2. Decisions

### Domain model and services stay together

In PoEAA terms, [Service Layer](https://martinfowler.com/eaaCatalog/serviceLayer.html) is a thin boundary that delegates to the [Domain Model](https://martinfowler.com/eaaCatalog/domainModel.html). They are tightly coupled by design — the service layer's job is to orchestrate the domain. Splitting them into separate modules or repos creates coordination overhead with no payoff. Same codebase, same module, same release cadence.

### Defer the Maven module extraction

Extracting `services` into its own Maven module is premature until there is a second consumer in the tree (or imminently). Module boundaries are easy to add and hard to remove: cross-module refactors get harder, poms multiply, and version coordination begins. A **package split inside the existing module** captures the dependency direction (clients → core) at ~5% of the cost.

### Defer the rename

Renaming `plugin-tasks-java` → `cli` advertises a generality the codebase does not yet have. Revisit once the web client lands and the CLI is genuinely one client among several.

### Layered modularization is an anti-pattern when…

- **Layers are anemic** — a "domain" package that is just data classes with getters, all behavior pushed to services. This is Fowler's [Anemic Domain Model](https://martinfowler.com/bliki/AnemicDomainModel.html) warning. The fix is putting behavior on domain objects, which dissolves much of the service layer.
- **The split is horizontal, not vertical** — splitting by *technical layer* (domain/service/api) means every feature change touches every module. Vertical slicing by feature/bounded context (tasks, plans, integration) keeps changes local.
- **The split precedes pain** — speculative modules ossify before the real seam reveals itself.

A package split avoids all three traps so long as behaviour stays on the domain objects and the only enforced rule is the dependency direction.

---

## 3. Proposed package layout

```
io.bitken.ss
├── core/                    ← domain + services (the "library")
│   ├── workflow/            ← domain: plans, tasks, state machine
│   ├── ledger/              ← domain: ledger entries, events
│   ├── integration/         ← domain: integrate flow
│   ├── stability/           ← domain
│   ├── git/                 ← gateway to external system (PoEAA Gateway)
│   └── service/             ← Service Layer — thin orchestration
│       ├── PlanService
│       ├── TaskService
│       └── IntegrationService
├── cli/                     ← Picocli commands, stdout formatting
│   └── (was: commands/)
├── api/                     ← shared DTOs + transport-neutral request/response
│   └── (PoEAA Data Transfer Objects — add only when needed)
├── web/                     ← HTTP handlers, JSON serialization
└── di/                      ← wiring (stays at top — knows about everything)
```

### Why this shape

- **`core` is the deployable unit.** CLI, web, and any future client depend on `core`. They never depend on each other.
- **`api` (DTOs) is the contract between core and clients.** This is PoEAA's [Data Transfer Object](https://martinfowler.com/eaaCatalog/dataTransferObject.html). Add it when the web client genuinely needs a different shape from the domain — not before.
- **Service Layer stays thin.** Behavior lives on `Plan`, `Task`, `LedgerEntry`, etc. Services orchestrate cross-aggregate operations and transactions. Watch for services that grow long methods doing what the domain should do.

### The one rule to enforce

**`core` must not import from `cli`, `api`, or `web`.**

Everything else is taste. A `grep` check works to start; ArchUnit can be added later if it matters.

---

## 4. Sequencing

1. Move `commands/` → `cli/` (rename only, no logic change).
2. Group domain packages under `core/` (`workflow`, `ledger`, `integration`, `stability`, `git`, `service`).
3. Add `web/` when starting the web client. Let its needs reveal whether `api/` DTOs are required, rather than building them speculatively.
4. Revisit the Maven module extraction and the `plugin-tasks-java` → `cli` rename once the web client exists and the seam is proven.

---

## 5. Open questions

- Which existing classes move into `core/service/` vs. stay as domain behaviour? (Audit during step 2.)
- Does `git/` belong inside `core/` as a gateway, or alongside it as infrastructure? Leaning gateway-inside-core since the domain references it through an interface.
- When `web/` lands, will it share `di/` wiring with `cli/`, or have its own composition root?
