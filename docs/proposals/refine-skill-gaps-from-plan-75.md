# Refine-skill improvements surfaced by plan-75

**Status:** proposal (for a future plan to pick up)
**Date:** 2026-06-11
**Source work:** plan-75 — *Prod release leaks experimental surface + stale build constants*

## Provenance (for the future implementer)

This proposal is derived from a retrospective of plan-75 against the
`experimental-refine` skill (`skills/experimental/refine/`).

- Plan file: `.agents/plans/plan-75.md`
- Branch: `t/75-prod-experimental-leak`
- Plan tags: `plan-75-v1` … `plan-75-v6`, `plan-75-complete`
- Commit range: **`6dd6bf5`** (branch base on `main`, "chore: bump version to 0.3.17")
  → **`89561c4`** (`plan-75-complete`). 43 commits.
- Key implementation commits to study for the examples below:
  - `8ba3f63` — consolidate the `build.env` prod-derivation into `buildSrc/BuildEnv.kt`
    (the cross-module single-source fix)
  - `7d396e4` — model build env as a typed `BuildEnv` enum (string literals → typed value)
  - `0b75417` / `5ae1e2b` — safe-default flip (`?: true` → `?: false`) + fail-loud version guard
  - `90f9e86` — `ReleaseGuard` harden (the all-static class discussed below)

## TL;DR

The refine skill **works** — applied to plan-75's own output it correctly flags real
issues. But plan-75's highest-leverage refactors were **cross-module / cross-language
single-source-of-truth** fixes and **safe-default / fail-loud** decisions, and the skill
has no rule for either. It is also implicitly Java-only, while the actual root-cause bug
lived in Kotlin/Gradle build scripts.

---

## Part A — Where applying the skill *would have helped* (misses in this plan)

These are cases where the skill, as written, already covers the issue but it was not
applied to plan-75's own net-new code. The `start` workflow's harden phase says "if
`experimental-refine-dev` exists, use it" — that step was skipped on the new Java.

### A1. `ReleaseGuard` is an anemic, all-static utility (commit `90f9e86`)

`packaging/.../ReleaseGuard.java` is a `final` class with **9 static methods and 0
instance fields**. `jdkHome` and `expectedVersion` are threaded through nearly every
call instead of being fields. This is exactly the anti-pattern the skill's **top three
rules** target:

- `rich-domain` — behaviour should live on an object that owns its data, not in static
  "Doer" methods.
- `class-structure` / `static-rare` — resolve collaborators once in a constructor;
  `static` should be rare.

A refine pass would produce `new ReleaseGuard(jdkHome, expectedVersion)` with
`verifyImageConstants(image)` / `verifyLauncher(image)` as instance methods.

### A2. `assertLauncherOutputIsProd(String, String, String)` — primitive obsession

Three same-typed `String` params (`versionOutput`, `helpOutput`, `expectedVersion`)
always travel together. `avoid-primitives` explicitly covers "the same two or more
primitively typed variables always seen together" and authorises creating a record
(e.g. `LauncherOutput`).

### A3. Stale doc comment left behind

`ReleaseGuard`'s class javadoc still describes the de-risk approach ("reads the
generated `Build.java`") after the harden switched to per-image `jimage`+`javap`.
Refine's clean-slate **Phase 2 regeneration** (rather than in-place patching) is
designed to prevent exactly this drift between code and comment.

**Takeaway:** none of these are skill gaps — they are a reminder that the harden phase
must actually run refine on net-new code, not only on pre-existing code being modified.

---

## Part B — Genuine gaps in the skill (new/extended rules)

### B1. Cross-boundary single source of truth (NEW RULE — highest value)

The skill's `single-source` rule is scoped to **literals within one class** (its example
is one class repeating `"~/.cache/app"` in two methods). But plan-75's single most
valuable refactor — and the **literal root cause of the shipped 0.3.17 bug** — was a
single rule computed independently across *three build scripts and a hand-coded field*:

- `experimental.enabled` was derived in `core/build.gradle.kts`, in `cli/build.gradle.kts`,
  and hand-set per-variant in `skills/pkg/build.gradle.kts`'s `RenderSpec`s. Because the
  copies could drift, a prod build silently shipped `EXPERIMENTAL_BUILD=true`.
- The fix (commit `8ba3f63`) hoisted the rule into `buildSrc/BuildEnv.kt`; now `core`,
  `cli`, and `skills/pkg` all call one definition.

The current rule gives no guidance for duplication that spans **files, modules, or
languages**. Proposed new rule — *"derive, don't duplicate, across boundaries"*:

> When the same decision/value is computed in more than one file, module, or language,
> hoist the *rule* to a single shared definition (a `buildSrc` helper, a shared enum, an
> injected config object) and have every site call it. Treat "two places compute the
> same prod/dev/feature decision" as a defect even when each copy is individually
> correct — correct-but-duplicated is how they silently drift. A value that crosses a
> process boundary as a string (a `-P` property, a system property, an env var) should
> be a *typed* value with exactly one parse point.

### B2. Typed values over stringly-typed boundary crossings (extend `avoid-primitives`)

Related to B1 but distinct: plan-75 replaced scattered `"dev"`/`"prod"` string literals
with a typed `enum class BuildEnv(val value: String)` carrying `.value` (wire form) and
`from(String?)` (one parse point) — commit `7d396e4`. `avoid-primitives` is the natural
home, but its examples are all in-class method params; it should explicitly call out
**config/CLI/build values that cross a boundary as strings** as a prime candidate for a
typed wrapper with a single parse/serialize point.

### B3. Safe defaults & fail-loud on ambiguous input (NEW RULE)

Plan-75 repeatedly turned silent-wrong behaviour into safe-default-or-loud-failure — a
recurring human steer, and a genuine design-quality dimension the skill omits entirely:

- absent flag → default to the **safe** value, not the convenient one
  (`?: true` → `?: false`; commit `0b75417`)
- missing/blank required value → **throw**, don't stamp a stale literal
  (`plugin.version` blank → `GradleException`; commits `0b75417`/`5ae1e2b`)
- unrecognised enum input → **throw**, don't silently resolve to a half-meant variant
  (`BuildEnv.from("staging")` → `GradleException`; commit `7d396e4`)

Proposed rule — *"fail closed, fail loud"*:

> When a default must be chosen for an absent/ambiguous input, prefer the *safe* outcome
> (the one that fails closed) over the convenient one. When a required input is missing,
> blank, or unrecognised, fail loudly at the earliest point (throw / abort the build),
> rather than substituting a guessed or stale value that ships silently.

### B4. The skill is implicitly Java-only; correctness lived in the build scripts

Every example, the package-boundary guidance (`cli/`, `conf/`, `workflow/`), and the
`@import PluginModel` framing assume Java. But plan-75's defect and its best fixes were
in **Kotlin/Gradle build scripts** (`*.gradle.kts`, `buildSrc/*.kt`) — code a Java-scoped
refine pass would never look at. The OO rules (single-source, avoid-primitives,
constructor-DI, rich-domain) apply directly to Kotlin too. The skill should state that
its rules cover build scripts and other JVM-language sources, not only `src/main/java`,
and that build-time configuration logic is in scope for refinement.

---

## Suggested implementation shape (for the future plan)

1. New rule file `rules/single-source-across-boundaries.jte.md` (B1), wired into
   `SKILL.jte.md` near the existing `single-source` include and added to the rule-priority
   list just under "Single source of truth".
2. New rule file `rules/fail-closed-loud.jte.md` (B3).
3. Extend `rules/avoid-primitives.jte.md` with the boundary-crossing-string case (B2).
4. A short scope note in `SKILL.jte.md` that the rules apply to all JVM-language sources
   incl. Gradle/Kotlin build scripts (B4).
5. (Process, not skill) reinforce in the `start` harden step that refine runs on
   *net-new* code too — see Part A.

Each rule file already follows a Good/Bad/"Why this matters" structure; the plan-75
commits above provide ready-made real-world Before/After material.
