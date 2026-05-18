# Feature flags via strongly-typed visitables

**Status:** Draft proposal (pre-plan-42)  
**Author:** Pramod, with AI design assistance  
**Supersedes interface from:** plan-41 (`FeatureFlags#isExperimental()`)

---

## 1. Why revisit plan-41?

Plan-41 shipped a hard gate for the 10 experimental `shipsmooth-tasks`
subcommands via a single marker method:

```java
public interface FeatureFlags {
    default boolean isExperimental() { return false; }
}
```

This is a **static property of the command class**: whether a command is
experimental is hardcoded into its source. The CLI partitions commands at
construction by `cmd instanceof FeatureFlags ff && ff.isExperimental()`,
and the runtime `--enable-experimental` arg decides whether the
experimental subset is registered.

Two limitations motivate this proposal:

1. **No second consumer.** "Experimental" is only one possible gate.
   Future gates (beta features, kill-switches, per-build-profile toggles,
   admin-only commands) would each need a new boolean on the marker
   interface, with the CLI's registration logic learning a new branch
   per family.

2. **Policy lives in the command, not in config.** The decision "this
   command is experimental" is baked into the class file. You can't
   flip it without recompiling. A more flexible system would let the
   class declare *which gate* applies, and let injected config decide
   *whether the gate is currently passing*.

This proposal replaces the marker interface with a strongly-typed flag
abstraction. The hide-in-`--help` mechanism via the Maven-filtered
`Build.EXPERIMENTAL_BUILD` constant from plan-41 is unchanged — it remains
the right tool for a compile-time annotation constant.

---

## 2. Approach being considered: one typed pipeline per flag family

### 2.1 Shape

Each "flag family" (experimental, beta, …) is a typed pipeline through
the system:

- A **context interface** describes the data a flag of that family needs.
- A **visitable interface** is implemented by any UI that can supply
  such a context.
- A **flag interface** is parameterised by the visitable type:
  `FeatureFlag<V>`.
- The application component (Dagger) exposes **one list per family**,
  typed `List<HasFeatureFlag<V>>`. There is no heterogeneous
  `List<FeatureFlag<?>>`.
- The UI has **one registration loop per family**. Because the list is
  typed, `flag.isEnabled(this)` only compiles when the UI implements
  the corresponding visitable.

### 2.2 Core types

```java
// Marker root for the data a flag reads.
public interface FlagContext {}

// What a flag needs from the calling UI, parameterised by visitable.
public interface FeatureFlag<V> {
    String id();
    boolean isEnabled(V visitable);
}

// Commands that are gated carry their family in their declaration.
public interface HasFeatureFlag<V> extends Callable<Integer>, HasSpec {
    FeatureFlag<V> flag();
}
```

### 2.3 An "experimental" family

```java
public interface ExperimentalContext extends FlagContext {
    /** True if the user passed the experimental opt-in token. */
    boolean isExperimentalOptIn();
}

public interface ExperimentalVisitable {
    ExperimentalContext experimentalContext();
}

public final class ExperimentalFlag implements FeatureFlag<ExperimentalVisitable> {
    private final String id;
    private final Config config;        // Dagger-injected

    public ExperimentalFlag(String id, Config config) {
        this.id = id; this.config = config;
    }

    @Override public String id() { return id; }

    @Override public boolean isEnabled(ExperimentalVisitable v) {
        if (config.killSwitch(id)) return false;
        return v.experimentalContext().isExperimentalOptIn();
    }
}
```

### 2.4 A gated command

```java
public final class IntegrateCommand
        implements HasFeatureFlag<ExperimentalVisitable> {
    private final FeatureFlag<ExperimentalVisitable> flag;

    @Inject IntegrateCommand(
            @Named("integrate.flag") FeatureFlag<ExperimentalVisitable> flag,
            /* deps */) {
        this.flag = flag;
    }

    @Override public FeatureFlag<ExperimentalVisitable> flag() { return flag; }
    // getSpec(), call(), etc.
}
```

### 2.5 Dagger component — typed buckets per family

```java
@Component(modules = { ... FeatureFlagsModule.class })
public interface TasksAppComponent {
    @Named("ungated")      List<Callable<?>>                              ungatedCommands();
    @Named("experimental") List<HasFeatureFlag<ExperimentalVisitable>>    experimentalCommands();
    // future:
    // @Named("beta")      List<HasFeatureFlag<BetaVisitable>>            betaCommands();
}
```

The flag module produces a `FeatureFlag<ExperimentalVisitable>` per
experimental command, all backed by `ExperimentalFlag` with different
ids.

### 2.6 UI side — one loop per family

```java
public final class TasksCli implements ExperimentalVisitable /*, BetaVisitable, ... */ {

    private final List<Callable<?>> ungated;
    private final List<HasFeatureFlag<ExperimentalVisitable>> experimental;
    private final CommandSpec rootSpec;

    private ExperimentalContext experimentalContext;
    @Override public ExperimentalContext experimentalContext() { return experimentalContext; }

    public int execute(String... args) {
        ParseResult probe = probeParse(args);
        this.experimentalContext = new CliExperimentalContext(
            probe.matchedOptionValue("--enable-experimental", false));

        for (Callable<?> c : ungated) register(c);

        for (HasFeatureFlag<ExperimentalVisitable> c : experimental) {
            if (c.flag().isEnabled(this)) register(c);
        }

        return new CommandLine(rootSpec).execute(args);
    }
}
```

If `TasksCli` grows large, the per-family registration is mechanically
extractable into a sibling class `CommandRegistrar` without changing the
shape of any of the types above. The proposal does not prescribe whether
to extract — that's a code-organisation choice.

### 2.7 What adding a new flag family looks like

To add (e.g.) `BetaFlag`:

1. New interfaces: `BetaContext extends FlagContext`, `BetaVisitable`.
2. New concrete: `BetaFlag implements FeatureFlag<BetaVisitable>`.
3. Component: add `@Named("beta") List<HasFeatureFlag<BetaVisitable>> betaCommands()`.
4. `TasksCli implements …, BetaVisitable` — add field, accessor, and a
   second `for` loop in `execute()`.

Step 4 is where the **compile-time guarantee lives**: forget the
`implements BetaVisitable` clause, and `c.flag().isEnabled(this)` in the
new loop fails to compile.

### 2.8 Adding a new flag within an existing family (e.g. `enable.FeatureX`)

Scenario: `FeatureXCommand` is a new experimental subcommand. The
`ExperimentalFlag` family already exists.

**Step 1 — the command class** (you were writing this anyway):

```java
public final class FeatureXCommand
        implements HasFeatureFlag<ExperimentalVisitable> {

    private final FeatureFlag<ExperimentalVisitable> flag;

    @Inject FeatureXCommand(
            @Named("featureX.flag") FeatureFlag<ExperimentalVisitable> flag,
            /* other deps */) {
        this.flag = flag;
    }

    @Override public FeatureFlag<ExperimentalVisitable> flag() { return flag; }

    @Override public CommandSpec getSpec() { ... }

    @Override public Integer call() { ... }
}
```

**Step 2 — one `@Provides` binding** in `FeatureFlagsModule`:

```java
@Provides @Named("featureX.flag")
static FeatureFlag<ExperimentalVisitable> featureXFlag(Config config) {
    return new ExperimentalFlag("experimental.featureX", config);
}
```

**Step 3 — add to the multibinding** for the experimental list (one line
in whichever `@Module` assembles `@Named("experimental") List<…>`):

```java
@IntoList @Named("experimental")
static HasFeatureFlag<ExperimentalVisitable> featureXCommand(FeatureXCommand c) {
    return c;
}
```

**No other changes.** No new interfaces. No UI changes. `TasksCli`'s
existing experimental loop picks up `FeatureXCommand` automatically.

**Call site** — how `FeatureXCommand` is surfaced to the user:

```
# Without flag: unmatched argument, exit 2
shipsmooth-tasks feature-x --help

# With flag: registers and runs normally
shipsmooth-tasks --enable-experimental feature-x --help
```

The flag decides this entirely via `ExperimentalFlag.isEnabled()`, which
reads `v.experimentalContext().isExperimentalOptIn()` — the same path
every other experimental command uses. `FeatureXCommand` contains zero
enablement logic.

### 2.9 Properties this design guarantees

- **No `HasFeatureFlag<?>` wildcard anywhere.** Every list, loop, and
  cast carries its `V`. Java erasure has nothing to erase.
- **No reflection, no `Class.isInstance`, no unchecked casts.**
- **Misclassifying a command is a compile error** at the Dagger module
  level: a `@Provides` returning the wrong typed list fails to compile.
- **Forgetting to implement a visitable on the UI is a compile error**
  in the registration loop.
- **The CLI's set of supported flag families is its `implements`
  clause** — visible in the IDE.

### 2.10 Cost

- **Source code growth:** two distinct axes:
  - *New flag within an existing family* (e.g. a new experimental command):
    one `@Provides` binding + one multibinding entry. No new types, no UI
    changes. Effectively free beyond the command class itself.
  - *New flag family* (a new gate kind: beta, admin, …): ~5 small
    interfaces + ~1 concrete flag class + 1 Dagger binding stanza + 1 UI
    loop + 1 `implements` clause. Roughly 80–150 LOC. Happens rarely
    (expected cadence: once every several months at most).
- **Runtime cost:** negligible. Per CLI invocation: one extra picocli
  probe parse (already in plan-41), N flag-method calls (N = gated
  commands, currently 10), each a virtual call the JIT inlines. Total
  added wall-clock budget < 100 µs cold, sub-µs warm — invisible
  against ~100 ms JVM + jlink startup.
- **Build/startup cost:** trivial. A handful of extra Dagger nodes,
  a few interfaces, < 100 KB jlink image growth.

### 2.11 Tradeoff vs. plan-41

- **Lost:** simplicity (one marker method beats five-types-per-family).
- **Gained:** open-closed for new flag kinds without modifying existing
  flag classes; policy moves from command source code into injected
  config; compile-time enforcement that the UI can satisfy every flag
  it surfaces.

### 2.12 Unchanged from plan-41

- The Maven-filtered `Build.EXPERIMENTAL_BUILD` compile-time constant
  driving `@Option(hidden = !Build.EXPERIMENTAL_BUILD)` for the
  `--enable-experimental` option in prod builds. Annotation `hidden`
  requires a compile-time constant; DI-injected config cannot drive it.
- The probe-parse strategy for reading `--enable-experimental` before
  the real parse. The probe still feeds the registration decision; it
  just feeds it into typed contexts rather than a boolean field.
- The 10 experimental command classes still ship in the JAR — gating is
  about registration, not compilation.

---

## 3. Main alternative considered: heterogeneous "context bag" visitable

Before settling on per-family typed pipelines, we explored a single
visitable carrying a heterogeneous bag of contexts.

### 3.1 Shape

```java
public interface FlagContexts {
    <C extends FlagContext> Optional<C> get(Class<C> type);
}

public interface FeatureFlag {
    String id();
    boolean isEnabled(FlagContexts contexts);
}
```

The UI builds a `FlagContexts` instance containing every context type
it knows how to supply, then hands the bag to every flag. Each flag
reaches into the bag for the context types it cares about:

```java
final class ExperimentalFlag implements FeatureFlag {
    @Override public boolean isEnabled(FlagContexts contexts) {
        if (config.killSwitch(id)) return false;
        return contexts.get(ExperimentalContext.class)
                       .map(ExperimentalContext::isExperimentalOptIn)
                       .orElse(false);
    }
}
```

### 3.2 What it gains over the per-family approach

- **No dispatcher.** The CLI's main loop is one `for` over a single
  `List<HasFeatureFlag>`. New flag families add zero UI-side
  registration code.
- **No proliferation of visitables / implements clauses.** Each new
  context type is a new key in the bag; the UI just calls
  `builder.add(MyContext.class, instance)`.
- **`HasFeatureFlag` is ungenericised.** Commands don't carry an
  `<F extends FlagContext>` type parameter.

### 3.3 Why it was rejected

- **Silent-disable failure mode.** If the UI forgets to add (say)
  `ExperimentalContext` to the bag, every experimental flag silently
  returns false. No error, no log entry. Every experimental command
  disappears from `--help`. This is exactly the kind of bug that ships
  and is noticed weeks later in production.
- **No compile-time check that the UI can satisfy a flag's needs.**
  Wiring a flag that reads `AdminContext` to a UI that doesn't supply
  it produces an empty `Optional` at runtime, indistinguishable from
  "user is not admin."
- **Magic-string-equivalent via `Class` keys.** Less stringly-typed
  than literal strings, but still a runtime lookup; the relationship
  "this flag depends on this context type" is not visible in any
  type signature.

### 3.4 When it would have won

If the codebase expected **many flag families** (5+) and **few UIs**
(1–2), the linear-per-family cost of the chosen design would dominate
and the bag's "open-closed for new families" property would be worth
the silent-failure risk. For this codebase — one UI today, perhaps
one or two more over the next few years, and 1–3 flag families on the
same horizon — the safety guarantees of the typed pipeline outweigh
the boilerplate.

---

## 4. Other alternatives briefly considered and discarded

- **Keep `isExperimental()` as a static class property** (the plan-41
  marker). Flat: doesn't generalise to a second flag family without
  adding sibling booleans, and policy still lives in the command class
  rather than injected config.
- **`FeatureFlag<V>` with a single heterogeneous list + runtime
  `Class.isInstance(this)` check.** Compile-passes-runtime-fails when
  a flag's `V` is not implemented by the UI. Strictly worse than the
  per-family lists, which catch the same mistake at compile time, with
  no perf benefit to compensate.
- **Sealed flag hierarchy + exhaustive pattern matching (Java 21+).**
  Gives the same compile-time exhaustiveness as the typed pipeline,
  but couples a flag's family to the class hierarchy and removes the
  ability for Dagger to compose flags from injected config dynamically.

---

## 5. Open questions for review

1. **Where does the `Config` type live and what does it carry?** This
   proposal references `config.killSwitch(id)` as a sketch only. Real
   shape (env-var-backed, file-backed, both) belongs to a separate
   design.
2. **Should `HasFeatureFlag<V>` extend `Callable<Integer>` and
   `HasSpec`?** Doing so removes one cast in the registration loop
   but couples the gating mechanism to the picocli-specific
   `HasSpec` interface.
3. **Code organisation:** keep registration in `TasksCli`, or extract
   per-family loops into a sibling `CommandRegistrar`? Pure
   organisational; doesn't affect any of the types above.
4. **Naming:** `FlagContext`, `FeatureFlag<V>`, `HasFeatureFlag<V>`,
   `*Visitable`, `*Context`. Open to better names — particularly
   "visitable" is borrowed from the Visitor pattern but doesn't fully
   match (there's no double-dispatch); something like `*ContextHost`
   or `*ContextProvider` may read better.
