# Plan 32: Migrate plugin-resources to JTE templating

## Context

The `plugin-resources` module uses `maven-resources-plugin` with `<filtering>true</filtering>` for all variable substitution. Pain points:
- Repetitive `<execution>` blocks (one per file per platform)
- No logic — can't do conditionals or loops in templates
- Delimiter juggling (`@...@` for hooks.json to avoid colliding with bash `${...}`)
- Every new platform adds dozens of XML lines

JTE (already used in `shipsmooth-site`) solves all of these: compile-time type safety, `@if`/`@for` logic, one runner instead of many XML executions.

**Backlog issue:** This is a build-infrastructure improvement with no corresponding Linear/backlog issue. Tracking locally only.

**Scope:** Phase 1 only — migrate `SKILL.md`. Phase 2 (remaining JSON files) is out of scope for this plan.

---

## Design

Add a new `plugin-resources-java` Maven module (`<packaging>jar</packaging>`) alongside the existing `plugin-resources`. This avoids changing the existing module's packaging (which would affect `plugin-dist`'s dependency on it).

The new module:
- Holds a `PluginModel` record and a `ResourceBuilder` main class
- Uses `jte-maven-plugin 3.1.15` to compile `.jte` templates at `generate-sources`
- Uses `exec-maven-plugin` to call `ResourceBuilder.main()` at `compile` phase, writing output to `${build.outputDir}`

Reference: `/opt/workspace/shipsmooth-site/pom.xml` and `SiteBuilder.java` for JTE wiring pattern.

Key decisions:
- `ContentType.Plain` (not Html) — Html would escape `<`/`>` in bash code blocks
- System properties (not constructor args) for model fields — cleaner for optional fields like `skill.frontmatter`
- `@project.version@` in `cliBin` is resolved by Maven before reaching Java — no special handling needed
- Add `<build.platform>` property to root pom profiles instead of per-module profile overrides

---

## Tasks

### Task 1: Wire up plugin-resources-java Maven module [High]

Create the new module directory, write `pom.xml` with `jte-maven-plugin`, `build-helper-maven-plugin`, and `exec-maven-plugin`, and register it in the root `pom.xml`. The module must compile cleanly (even with stub Java files) before any template logic is added.

- New file: `plugin-resources-java/pom.xml`
- Edit: root `pom.xml` — add `<module>plugin-resources-java</module>` and `<build.platform>` property to all three profiles

### Task 2: Implement PluginModel and ResourceBuilder [Medium]

Write the Java model record and the builder main class that reads system properties, constructs the model, and calls `engine.render()`.

- New file: `plugin-resources-java/src/main/java/com/github/pramodbiligiri/shipsmooth/resources/PluginModel.java`
- New file: `plugin-resources-java/src/main/java/com/github/pramodbiligiri/shipsmooth/resources/ResourceBuilder.java`

### Task 3: Write SKILL.jte and verify output matches baseline [Low]

Convert `SKILL.md` to a JTE template, run both the old Maven filtering and the new JTE pipeline, and diff the outputs for all three profiles (dev, prod, gemini). Remove the old `copy-skill` execution from `plugin-resources/pom.xml` once verified identical.

- New file: `plugin-resources-java/src/main/jte/skills/SKILL.jte`
- Edit: `plugin-resources/pom.xml` — remove `copy-skill` execution

---

## Risk Analysis

| Task | Risk | Justification |
|---|---|---|
| Task 1: Wire Maven module | High | First JTE module in this repo; `exec-maven-plugin` + precompiled JTE classpath ordering is subtle and untested here |
| Task 2: PluginModel + ResourceBuilder | Medium | Straightforward Java, but `skill.frontmatter` multiline string and `@project.version@` expansion need verification |
| Task 3: SKILL.jte + diff verification | Low | Mechanical conversion; risk is only in the diff step catching regressions |