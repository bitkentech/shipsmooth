# Plan 41 — Feature-flag gate for experimental CLI commands

**Status:** open
**Version:** v3 (also hide --enable-experimental from --help in prod builds)
**Branch:** `t/plan-41-experimental-gate`
**Tracking mode:** Local (`.agents/plans/plan-41-tasks.xml`).
**Depends on:** plan-40 (parallel-execution skill split, merged to main as 60ff636).

---

## 1. Context

Plan-40 split the parallel-execution workflow into its own skill
(`experimental-start-parallel(-dev)`), so the base `start-dev` skill no longer
documents the parallel commands. The Java CLI was deliberately left untouched
in plan-40 — all 10 experimental subcommands are still registered on
`shipsmooth-tasks` and runnable without any flag.

This plan adds the **hard gate**:

1. A top-level `--enable-experimental` option on `shipsmooth-tasks`.
2. The 10 experimental subcommands are **not registered** on the root spec
   unless the flag is present in the args. With the flag, they are
   registered before parse and behave normally.
3. Without the flag, `shipsmooth-tasks integrate ...` produces picocli's
   native "unmatched argument" error (exit code 2). No custom error
   message, no `IExecutionStrategy`.
4. With the flag, `shipsmooth-tasks --enable-experimental --help` lists
   the experimental subcommands; `shipsmooth-tasks --enable-experimental
   integrate ...` runs the command.
5. **Prod-build hiding:** in `-Pprod` / `-Pgemini` builds, the
   `--enable-experimental` option itself is hidden from `--help` output.
   The option is still parseable if someone types it (so the conditional
   registration logic is identical across builds), but it does not appear
   in help. In `-Pdev` / `-Pgemini-dev` builds the option is visible in
   `--help` as normal.

After the gate is in place, the CLI examples in
`_partials/parallel-execution.jte.md` are rewritten to include
`--enable-experimental` so the rendered `experimental-start-parallel(-dev)`
skill is self-consistent with CLI behaviour.

The experimental command **classes still ship** in the JAR — the goal is
"not registered without opt-in," not "not compiled in" (matches plan-40's
existing single-build-artifact decision).

---

## 2. Design notes

### Where does "experimental" live?

The classification is a **property of the command itself**, expressed via a
new marker interface. Any UI (TasksCli today, a hypothetical WebUI or IDE
plugin tomorrow) consumes the same answer by querying the command instance.
This satisfies "experimentality is a domain property" without inventing a
premature `Feature` taxonomy.

`plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/stability/FeatureFlags.java` (new):

```java
package io.bitken.shipsmooth.tasks.stability;

/**
 * Stability flags declared by a command. UIs use these to decide whether
 * to surface the command by default, gate it behind an opt-in flag, hide
 * it from help, etc.
 *
 * Defaults are "no flags set" — a command that doesn't override anything
 * is a stable, default-visible command. To make a command experimental,
 * implement this interface and override isExperimental() to return true.
 */
public interface FeatureFlags {
    /** True if this command is experimental and should not be registered
     *  unless the user explicitly opts in (e.g. via --enable-experimental). */
    default boolean isExperimental() {
        return false;
    }
}
```

The 10 experimental command classes implement `FeatureFlags` and override
`isExperimental()` to return `true`. Non-experimental commands need no
change — `TasksCli` treats `Callable<?>` instances that don't implement
`FeatureFlags` (or implement it without overriding) as default.

### Conditional registration via probe parse

The 10 experimental command names are **not registered** on the root
`CommandSpec` at construction time. They're held aside in a separate list.

`TasksCli.execute(String... args)` performs a **probe parse** before the
real parse:

- Create a `CommandLine` wrapping the same root spec (which currently
  contains only default subcommands).
- Set `unmatchedArgumentsAllowed(true)` and
  `unmatchedOptionsArePositionalParams(true)` so the probe doesn't error
  on tokens like `integrate` that aren't yet registered.
- Call `probe.parseArgs(args)` and read
  `probeResult.matchedOptionValue("--enable-experimental", false)`.
- If `true`, call `registerExperimentals()` to add the held-aside
  subcommands to the root spec.
- Call `cmd.execute(args)` against the (possibly updated) spec.

The probe respects picocli's normal rule that **top-level options precede
the subcommand**. If a user types `shipsmooth-tasks integrate
--enable-experimental ...` (flag after subcommand), the probe sees
`integrate` as unmatched, takes everything after it as positional, and
returns `enable = false`. The real parse then errors with "unmatched
argument: integrate." This is correct UX: the flag is a top-level flag
and must appear before the subcommand.

No `IExecutionStrategy` is involved — its contract is "execute, return
an exit code," not "mutate the spec." Mutation happens before the real
parse begins.

### What "experimental" applies to

The 10 commands (confirm exact subcommand-name strings from each
command's `getSpec()` before committing):
`claim`, `worker-init`, `worker-finish`, `worker-cleanup`, `worker-base`,
`integrate`, `ledger-watch`, `ledger-resolver-complete`,
`ledger-record-commit`, `ledger-record-patch-integrated`.

Non-experimental (unaffected): `init`, `show`, `update-status`,
`add-comment`, `add-deviation`, `set-commit`, `project-update`,
`ledger` (with its `list`/`verify`/`read` subcommands).

### SKILL.md update

After the gate is in place, `parallel-execution.jte.md` gets every
`${model.cliBin()} <experimental-subcommand> ...` rewritten to
`${model.cliBin()} --enable-experimental <experimental-subcommand> ...`.
A matching integration-test assertion confirms the rendered
`experimental-start-parallel-dev/SKILL.md` contains the flag.

### Prod-build hiding via Maven-filtered constant

A single compile-time constant `Build.EXPERIMENTAL_BUILD` controls whether
`--enable-experimental` is documented in `--help`:

`plugin-tasks-java/src/main/java-templates/io/bitken/shipsmooth/tasks/Build.java`
(new, Maven-filtered):

```java
package io.bitken.shipsmooth.tasks;
public final class Build {
    public static final boolean EXPERIMENTAL_BUILD = ${experimental.enabled};
    private Build() {}
}
```

The picocli `@Option` references it directly:

```java
@CommandLine.Option(
    names = "--enable-experimental",
    description = "Enable experimental subcommands.",
    hidden = !Build.EXPERIMENTAL_BUILD)
boolean enableExperimental;
```

`hidden` accepts a compile-time constant expression. Because
`EXPERIMENTAL_BUILD` is a `static final boolean`, javac inlines the value
into the annotation at compile time. The bytecode of `TasksCli` in a prod
build literally carries `hidden = true` for this option.

Maven profile additions to the **root** `pom.xml`:
- `dev` profile: `<experimental.enabled>true</experimental.enabled>`.
- `gemini-dev` profile: `<experimental.enabled>true</experimental.enabled>`.
- `prod` profile: `<experimental.enabled>false</experimental.enabled>`.
- `gemini` profile: `<experimental.enabled>false</experimental.enabled>`.

`plugin-tasks-java/pom.xml` adds `templating-maven-plugin` (1.0.0) to
filter `src/main/java-templates/` into `target/generated-sources/java-templates/`
during `generate-sources`. `build-helper-maven-plugin` (already in use for
the JTE-generated sources in `plugin-skill`) adds the generated directory
as an additional source root.

---

## 3. Tasks

### Task 1: Introduce FeatureFlags; conditionally register experimental commands [High]

1. **Create `FeatureFlags` interface** at
   `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/stability/FeatureFlags.java`
   (text above).

2. **Mark the 10 experimental commands.** Each of `ClaimCommand`,
   `WorkerInitCommand`, `WorkerFinishCommand`, `WorkerCleanupCommand`,
   `WorkerBaseCommand`, `IntegrateCommand`, `LedgerWatchCommand`,
   `LedgerResolverCompleteCommand`, `LedgerRecordCommitCommand`,
   `LedgerRecordPatchIntegratedCommand`:
   - Add `implements FeatureFlags` to the class declaration (alongside
     existing `HasSpec` / `Callable`).
   - Override `isExperimental()` to return `true`.

3. **Add Maven-filtered build constant.**

   - Root `pom.xml`: in each profile's `<properties>`, add
     `<experimental.enabled>true</experimental.enabled>` (for `dev` and
     `gemini-dev`) or `<experimental.enabled>false</experimental.enabled>`
     (for `prod` and `gemini`).
   - Create
     `plugin-tasks-java/src/main/java-templates/io/bitken/shipsmooth/tasks/Build.java`
     with the template shown in Design Notes.
   - In `plugin-tasks-java/pom.xml`, add the `templating-maven-plugin`
     binding so `java-templates/` is filtered into
     `target/generated-sources/java-templates/` during `generate-sources`,
     and add the generated directory as a source root via
     `build-helper-maven-plugin:add-source`.
   - Smoke-check: run `mvn -P dev -pl plugin-tasks-java compile`; confirm
     `target/generated-sources/java-templates/.../Build.java` exists and
     contains `EXPERIMENTAL_BUILD = true`. Repeat with `-P prod` and
     confirm `false`.

4. **Refactor `TasksCli`** at
   `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/TasksCli.java`:

   - Add a top-level option to the existing mixin:
     ```java
     @CommandLine.Option(names = "--enable-experimental",
         description = "Enable experimental subcommands.",
         hidden = !Build.EXPERIMENTAL_BUILD)
     boolean enableExperimental;
     ```
   - In the constructor, partition the existing command list into
     `defaultCommands` and `experimentalCommands` based on
     `cmd instanceof FeatureFlags ff && ff.isExperimental()`.
   - Register only `defaultCommands` on the root spec.
   - Store `experimentalCommands` as a field for later registration.
   - Add a constant
     `private static final String ENABLE_EXPERIMENTAL_FLAG = "--enable-experimental";`.

5. **Implement `execute(String... args)` with probe parse:**

   ```java
   public int execute(String... args) {
       CommandLine probe = new CommandLine(rootSpec);
       probe.setUnmatchedArgumentsAllowed(true);
       probe.setUnmatchedOptionsArePositionalParams(true);
       ParseResult probeResult = probe.parseArgs(args);
       if (probeResult.matchedOptionValue(ENABLE_EXPERIMENTAL_FLAG, false)) {
           for (Callable<?> c : experimentalCommands) {
               CommandSpec sub = ((HasSpec) c).getSpec();
               rootSpec.addSubcommand(sub.name(), sub);
           }
       }
       return cmd.execute(args);
   }
   ```

   The probe is a real picocli parse against the same root spec; the
   lenient flags allow it to succeed even with unknown subcommand names.

6. **No `IExecutionStrategy`.** Picocli's default execution handles
   normal subcommand dispatch; its native "unmatched argument" error
   handles the refusal case for unregistered experimental subcommands.

7. **Write `plugin-tasks-java/src/test/java/io/bitken/shipsmooth/tasks/TasksCliTest.java`** (new) covering:

   1. `new TasksCli(app).execute("integrate", "--help")` → exit 2.
      Stderr contains "unmatched" (picocli's native wording — confirm
      exact string and assert against it).
   2. `new TasksCli(app).execute("--enable-experimental", "integrate", "--help")`
      → exit 0, stdout contains integrate's usage line.
   3. `new TasksCli(app).execute("--help")` → exit 0, stdout does
      **not** contain "integrate" or any of the 10 experimental
      subcommand names.
   4. `new TasksCli(app).execute("--enable-experimental", "--help")` →
      exit 0, stdout contains all 10 experimental subcommand names.
   5. `new TasksCli(app).execute("show", "--help")` → exit 0
      (non-experimental command works without flag).
   6. `new TasksCli(app).execute("integrate", "--enable-experimental")` →
      exit 2 (flag after subcommand is misplaced; native error).
   7. **Prod-build help visibility.** The test runs under whichever Maven
      profile is active (`dev` by default). Assert that
      `new TasksCli(app).execute("--help")` stdout **contains**
      `--enable-experimental` when `Build.EXPERIMENTAL_BUILD == true`,
      and **does not contain** `--enable-experimental` when
      `Build.EXPERIMENTAL_BUILD == false`. Use a conditional in the test
      keyed on the constant — same test class works under all profiles.

Run `mvn -pl plugin-tasks-java test` — all green under `-P dev`.
Run `mvn -P prod -pl plugin-tasks-java test` and confirm the
prod-visibility branch of test 7 fires correctly.

### Task 2: Update parallel-execution partial to pass --enable-experimental [Medium]

*Depends-on: 1*

In `plugin-skill/src/main/jte-src/skills/_partials/parallel-execution.jte.md`,
prefix every `${model.cliBin()} <experimental-subcommand> ...` with
`--enable-experimental` (e.g.
`${model.cliBin()} --enable-experimental integrate --plan {N} ...`).

Also update any `claude/` or `gemini/` sub-partials referenced from
`parallel-execution.jte.md` that contain experimental CLI invocations.

Add an integration-test assertion to
`plugin-skill/src/test/java/io/bitken/shipsmooth/resources/ResourceBuilderIntegrationTest.java#experimentalParallelSkillIsRendered`:

```java
assertTrue(content.contains("--enable-experimental"),
    "experimental parallel skill should call the CLI with --enable-experimental");
```

Run `mvn -pl plugin-skill test` — all green.

### Task 3: End-to-end verification with the freshly built CLI [Low]

*Depends-on: 1,2*

Build the jlink image and exercise the gate with the real binary, in both
`-P dev` and `-P prod`:

```bash
# Dev build
mvn -P dev,jlink -pl plugin-tasks-java -am package
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --help
  # → no experimental commands listed; --enable-experimental IS listed
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks integrate --help
  # → exit 2, picocli's "unmatched argument" error
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --enable-experimental --help
  # → all 10 experimental commands listed
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --enable-experimental integrate --help
  # → integrate's usage prints

# Prod build
mvn -P prod,jlink -pl plugin-tasks-java -am package
<prod-runtime-path>/shipsmooth-tasks --help
  # → no experimental commands listed; --enable-experimental is NOT listed
<prod-runtime-path>/shipsmooth-tasks --enable-experimental --help
  # → --help output still doesn't mention the flag (it's hidden), but the
  # flag IS parsed and the experimental commands ARE listed in output.
  # This is the "still parseable in prod" behaviour from Design Notes §5.
```

Spot-check the rendered `build/skills/experimental-start-parallel-dev/SKILL.md`:
`grep --enable-experimental` should match every experimental command line.

---

## 4. Verification (end-to-end)

See Task 3 commands. Coverage threshold: 80% (matches plan-40 agreement).