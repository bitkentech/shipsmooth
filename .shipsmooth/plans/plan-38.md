# Plan 38 — Dagger 2 DI for CLI commands (foundation + AddCommentCommand)

**Status:** drafted, awaiting risk calibration & human go-ahead.
**Branch:** `t/plan-38-dagger-di`
**Tracking mode:** Local (`.agents/plans/plan-38-tasks.xml`).

---

## 1. Context

### Backlog feature (recorded here in lieu of an external backlog issue)

**Feature: CLI dependency injection foundation.** Migrate `plugin-tasks-java` from
inline service instantiation (`new XmlService()`, `new LedgerService(Paths.get("."))`)
to constructor-injected services, with Dagger 2 as the composition framework.

**Why this matters:**

- A future web UI / REST layer will need the same services injected into controllers
  with potentially different scopes (request, session). Today's inline `new`
  pattern in commands cannot be reused there.
- Future TLA-modelling-driven parallel workflow commands will share most of the
  same services and benefit from a uniform construction pattern instead of
  reinventing wiring per command.
- The user has signalled that future code will involve deep object graphs and
  per-request scopes. Manual wiring works today but won't scale to that shape.

**Why Dagger 2 specifically (decisions already made out-of-band):**

- Compile-time codegen → no reflection (preserves CLI cold-start).
- Explicit `@Module` / `@Component` wiring (matches the user's preference for
  Guice-style explicit modules over Spring-style autoconfiguration).
- Annotations available on constructors so tests *could* swap to a Dagger-based
  `TestComponent` later — but for plan-38 tests stay hand-wired.
- ~150 KB runtime JAR; jlink-trivial (no module-info surgery for Dagger itself
  beyond the standard `requires`).
- Dependency: `com.google.dagger:dagger:2.59.2` (user-chosen version).

### Current state (as of `main` at plan draft time)

- `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/commands/` contains
  18 picocli command classes. Each one constructs its services inline inside
  `call()`:

  ```java
  XmlService service = new XmlService();
  LedgerService ledger = new LedgerService(Paths.get("."));
  ```

- `TasksCli` is the single entry point. It hand-instantiates each command with a
  no-arg constructor and registers them with picocli.
- Plan 37 already extracted `IntegrateCommand`'s logic into `WorkflowServiceImpl`
  but did not change the construction pattern — `IntegrateCommand` still does
  `new WorkflowServiceImpl(Paths.get("."))` inside `call()`.
- Services involved for `AddCommentCommand`:
  - `XmlService` — stateless, no constructor args.
  - `LedgerService` — takes `Path repoRoot` at construction.

### Scope of this plan

**In scope:**
1. Add Dagger 2 dependency + annotation processor to `plugin-tasks-java/pom.xml`.
2. Add `requires` for Dagger to `module-info.java`. Open command/service packages
   to Dagger as needed.
3. Create the composition root: a Dagger `@Module` (e.g. `ServicesModule`) and a
   `@Component` interface (e.g. `AppComponent`) that exposes typed accessors for
   each command.
4. Refactor `TasksCli` to construct commands via the Dagger component instead of
   `new XxxCommand()`.
5. Migrate `AddCommentCommand` to constructor-injected `XmlService` +
   `LedgerService`. Add `@Inject` to its constructor.
6. Update tests for `AddCommentCommand` to construct services (or mocks)
   directly and pass them in. Tests do not touch Dagger.

**Out of scope (deferred to follow-up plans):**

- Migrating any other command. The other 17 commands remain unchanged — they
  continue to instantiate services inline. The Dagger component will list only
  `AddCommentCommand` initially. Follow-up plans (or plan-38 updates per the
  workflow's "minor deviation" path) extend the component as more commands
  migrate.
- Introducing scopes (`@Singleton` annotations on bindings are fine; custom
  scopes are not in scope).
- A `TestComponent` for tests. Tests stay hand-wired.
- Web/REST layer infrastructure.
- Any change to service shapes (e.g. `LedgerService(Path)` stays as-is — repo
  root is bound once at composition time via the module).

### Repo-root convention

The composition root will bind `Path repoRoot` to `Paths.get(".")` — matching
today's behaviour exactly. This is the **only** place `Paths.get(".")` should
appear in production code after this plan. Tests pass an explicit path.

---

## 2. Design

### 2.1 Dependency setup

**`pom.xml` additions** (keep existing entries intact):

```xml
<dependency>
    <groupId>com.google.dagger</groupId>
    <artifactId>dagger</artifactId>
    <version>2.59.2</version>
</dependency>
```

For codegen, add the Dagger annotation processor to the `maven-compiler-plugin`
configuration:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>com.google.dagger</groupId>
        <artifactId>dagger-compiler</artifactId>
        <version>2.59.2</version>
    </path>
</annotationProcessorPaths>
```

Generated sources land in `target/generated-sources/annotations/`. Maven picks
those up automatically.

### 2.2 module-info.java additions

```java
requires dagger;                              // Dagger runtime
requires jakarta.inject;                       // @Inject annotation
opens io.bitken.shipsmooth.tasks.di to dagger; // for generated DaggerAppComponent
```

(The `opens` for `commands` to picocli already exists; Dagger does not need
runtime reflection on commands because injection is constructor-based and
codegen-resolved.)

> **Note (verify at task 1):** Dagger 2.59 may require `requires
> jakarta.inject` versus `requires javax.inject` depending on which `@Inject`
> Dagger ships with. If neither resolves cleanly under JPMS we add the
> appropriate `inject-api` dependency explicitly. This is the most likely point
> of build friction — surface immediately if it doesn't resolve.

### 2.3 Composition root

New package: `io.bitken.shipsmooth.tasks.di`.

**`ServicesModule.java`:**

```java
@Module
public class ServicesModule {
    private final Path repoRoot;

    public ServicesModule(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Provides @Singleton
    Path provideRepoRoot() { return repoRoot; }

    @Provides @Singleton
    XmlService provideXmlService() { return new XmlService(); }

    @Provides @Singleton
    LedgerService provideLedgerService(Path repoRoot) {
        return new LedgerService(repoRoot);
    }
}
```

**`AppComponent.java`:**

```java
@Singleton
@Component(modules = ServicesModule.class)
public interface AppComponent {
    AddCommentCommand addCommentCommand();
}
```

Dagger codegen produces `DaggerAppComponent`:

```java
AppComponent app = DaggerAppComponent.builder()
    .servicesModule(new ServicesModule(Paths.get(".")))
    .build();
AddCommentCommand cmd = app.addCommentCommand();
```

### 2.4 `TasksCli` change

Today:
```java
Callable<?>[] commands = {
    new InitCommand(),
    new AddCommentCommand(),
    // ...17 more
};
```

After plan 38:
```java
AppComponent app = DaggerAppComponent.builder()
    .servicesModule(new ServicesModule(Paths.get(".")))
    .build();

Callable<?>[] commands = {
    new InitCommand(),
    app.addCommentCommand(),     // <-- only AddCommentCommand goes through Dagger
    new AddDeviationCommand(),
    // ...other 16 unchanged
};
```

The pattern is established; future plans extend `AppComponent` and swap more
`new XxxCommand()` calls for `app.xxxCommand()`.

**Test seam preservation:** `IntegrateCommand`'s `setResolverFactory` test seam
is unrelated to this plan. `TasksCli.integrateCommand()` continues to work as-is.

### 2.5 `AddCommentCommand` change

Today:
```java
public AddCommentCommand() {
    spec = ...;  // spec setup
}

public Integer call() {
    XmlService service = new XmlService();
    LedgerService ledger = new LedgerService(Paths.get("."));
    // ... use them
}
```

After plan 38:
```java
private final XmlService xmlService;
private final LedgerService ledgerService;

@Inject
public AddCommentCommand(XmlService xmlService, LedgerService ledgerService) {
    this.xmlService = xmlService;
    this.ledgerService = ledgerService;
    spec = ...;  // spec setup unchanged
}

public Integer call() {
    // ... use this.xmlService and this.ledgerService
}
```

Behaviour is byte-for-byte identical. The path `.agents/plans/plan-{N}-tasks.xml`
is still constructed inside `call()` — that's a path-derivation concern, not a
service-construction concern. Same for the warning behaviour when ledger
recording fails.

### 2.6 Test strategy

`AddCommentCommand` tests today live in (verify at task 1):
- `CommandsTest.java`
- `CommandLedgerTest.java`

These tests construct commands via `TasksCli` or directly. After the migration:

- Direct-construction tests use `new AddCommentCommand(new XmlService(), new
  LedgerService(tmpDir))` — explicit, no framework.
- Tests that go through `TasksCli` continue to work because `TasksCli` itself
  builds the Dagger component internally.

No `TestComponent`. No Dagger imports in test sources. If a future test wants
mocks, it constructs them with Mockito (already a dep? — check at task 1) and
passes them in directly.

---

## 3. Risk analysis (default risk levels — please calibrate)

| # | Task | Default risk | Justification |
|---|------|--------------|---------------|
| 1 | Add Dagger dependency + annotation processor; verify codegen runs and JPMS resolves | **Medium** | Build-system change. JPMS + Dagger combinations can have subtle module-path issues; `requires jakarta.inject` vs `javax.inject` is the likely friction point. |
| 2 | Create `di` package: `ServicesModule` + `AppComponent` (initially empty of commands) | **Low** | Self-contained, no behavioural change. |
| 3 | Migrate `AddCommentCommand` constructor + register in `AppComponent` + `TasksCli` | **Medium** | Behavioural-equivalence-critical. Existing tests must remain green with no edits to assertions. |
| 4 | Update direct-construction tests for `AddCommentCommand` to pass services in | **Low** | Mechanical edit. |

Reorder per workflow rule (descending risk, dependencies respected): order is
already 1 → 2 → 3 → 4 with 2 depending on 1, 3 on 2, 4 on 3.

---

## 3a. Tasks (risk-sorted)

### Task 1: Add Dagger dependency and verify codegen [Medium]

Add `com.google.dagger:dagger:2.59.2` to `plugin-tasks-java/pom.xml`. Configure
`maven-compiler-plugin` to run `dagger-compiler` as an annotation processor.
Add `requires dagger;` and (likely) `requires jakarta.inject;` to
`module-info.java`. Verify `mvn compile` produces sources under
`target/generated-sources/annotations/` and `mvn test` still passes with no
production code yet referencing Dagger.

### Task 2: Create di package with ServicesModule and AppComponent [Low]

*Depends-on: 1*

Create `io.bitken.shipsmooth.tasks.di.ServicesModule` with `@Provides` methods
for `Path repoRoot`, `XmlService`, and `LedgerService`. Create
`io.bitken.shipsmooth.tasks.di.AppComponents` as a `@Component` interface with
no command accessors yet. Add `opens io.bitken.shipsmooth.tasks.di to dagger;`
to `module-info.java`. Verify `mvn compile` generates `DaggerAppComponent`.

### Task 3: Migrate AddCommentCommand to constructor injection [Medium]

*Depends-on: 2*

Add `@Inject` constructor to `AddCommentCommand` taking `XmlService` and
`LedgerService`. Remove the inline `new XmlService()` and `new
LedgerService(Paths.get("."))` from `call()`. Add an `addCommentCommand()`
accessor to `AppComponent`. Update `TasksCli` to build a Dagger component once
and obtain `AddCommentCommand` via `app.addCommentCommand()` (other 16
commands remain unchanged). Behaviour must be byte-for-byte identical: same
stdout, stderr, exit code, XML mutation, ledger event.

### Task 4: Update AddCommentCommand tests for constructor injection [Low]

*Depends-on: 3*

Find tests in `CommandsTest` and `CommandLedgerTest` (and any other test
sources) that construct `AddCommentCommand` directly with `new
AddCommentCommand()`. Update them to pass services explicitly: `new
AddCommentCommand(new XmlService(), new LedgerService(tmpDir))`. Tests that
construct via `TasksCli` need no change. Do not introduce Dagger imports in
test sources. Coverage for `AddCommentCommand` must meet the agreed
threshold.

---

## 4. Test plan

**Integration test (preamble, before any task):**

1–2 integration tests that exercise the end-to-end "AddCommentCommand wired
through Dagger via TasksCli" flow:
- Test A: construct `TasksCli`, run `add-comment --plan N --task T --message "x"`
  against a temp repo, assert the XML was mutated and the ledger event was
  written.
- (Optionally) Test B: same flow but with `LedgerService` pointed at a
  read-only path → command still succeeds (warning path), XML still mutated.

These tests likely already exist in `CommandsTest` / `CommandLedgerTest`. If so,
they serve as the integration tests directly; we do **not** add duplicates. If
they do not cover the Dagger-wired path, we extend them — but the assertions
remain identical to today's (behaviour is unchanged).

**Per-task unit tests:** see §3 risk table. Each task adds the necessary unit
test before implementation (per Core Invariant #6).

**Coverage threshold:** to be confirmed with the human at Phase 2 step 0
(default 95%, but may be lower for this codebase — verify).

---

## 5. Risks & mitigations

**JPMS module resolution for Dagger.** Dagger 2.59 + Java module system can
require specific `requires` directives. Mitigation: task 1 ends only when
`mvn compile` and `mvn test` both pass on a fresh build. If JPMS friction is
worse than expected (e.g. Dagger needs `--add-opens` or fragile workarounds),
surface as a major deviation rather than papering over it.

**Behavioural drift in `AddCommentCommand`.** The migration must be
byte-for-byte equivalent — same stdout, same stderr, same exit code, same XML
mutation, same ledger event. Mitigation: keep all existing tests green without
assertion edits. Any test that needs an assertion change is a behaviour change
and triggers review.

**`TasksCli` startup cost.** Dagger codegen produces a tiny factory; the
component build is microseconds. We do not expect a measurable cold-start
regression but will spot-check `time runtime-0.2.0/bin/shipsmooth-tasks --help`
before and after as a sanity check.

**Annotation-processor build complexity.** First time the project uses an APT.
Generated sources land in `target/generated-sources/annotations/` — confirm
IDE-friendliness (IntelliJ usually picks these up automatically). If the IDE
can't see `DaggerAppComponent`, document the IDE setting in the plan rather
than working around it in code.

---

## 6. Open questions

1. **`@Inject` source.** Dagger 2.59 ships with `jakarta.inject`. Confirm at
   task 1 that no extra `<dependency>` is needed.
2. **Mockito availability.** If tests want mocks for `XmlService` or
   `LedgerService`, is Mockito on the classpath? If not, do we add it under
   `<scope>test</scope>` as part of task 4, or hand-roll a stub? Decide at
   task 4 based on what the existing tests already use.
3. **Coverage threshold for this module.** Confirm at Phase 2 step 0.

---

## 7. Closeout

On all four tasks `agent-coded` and integration green:

- Tag `plan-38-complete`, push.
- Note in this file's "Status" line: shipped.
- Pattern is now established for follow-up plans (39, 40, …) to migrate the
  other 17 commands one or two at a time.
