## Hybrid XML Rule Storage & Polymorphic Selection Architecture

| Metadata Field | Value |
| :--- | :--- |
| **Project** | ShipSmooth (Autonomous AI Developer Workflow Engine) |
| **Status** | Proposed |
| **Date** | May 2026 |
| **Target System** | Java CLI Engine, driven directly and via IDE plugins (Cursor & VSCode) |
| **Artifact Type** | Format Evaluation & Design Proposal |

---

## 1. Context & Goals

ShipSmooth executes autonomous AI coding and refinement workflows based on structured
engineering principles (e.g. Single Responsibility Principle, Primitive Obsession elimination,
strict nesting depth limits). Today these rules live hardcoded as Markdown sections inside a
single skill template (`refine/SKILL.jte.md`). To deploy across varied codebases — including
polyglot projects that mix Java, TypeScript, CSS, and more — and to integrate with developer
IDEs, the engine needs a configuration subsystem that breaks this monolith into individual,
selectively toggled, per-language rules.

All access to the rules — both reads and writes — goes through the Java CLI (the jlink
image). The Cursor / VSCode extension does not parse or rewrite the files itself; it invokes the
existing `shipsmooth` command, which performs the read or the toggle. There is no JavaScript in
the access path. A consequence used throughout this proposal: the UI is a **projection** the CLI
computes over the stored rules, not a mirror of on-disk layout. How rules are grouped or split on
disk is therefore independent of how they are presented (e.g. one UI tab per language).

### Goals (ranked)

1. **UI-driven enable/disable as the primary path.** A user must be able to quickly toggle
   individual rules on or off from a web front end or a Cursor / VSCode extension. In steady
   state this is how the file is written; hand-editing is the exception, not the norm.
2. **Occasional hand-editing with optional inline rules.** A user must be able to open the file
   and edit it by hand, and must be free to keep a rule's body inline. A mandatory external file
   per rule is unacceptable; inline must be a first-class option.

### Derived requirements

* **Polymorphic inlining freedom.** Short rules may stay fully inline in the central file;
  longer rules may be extracted to a standalone external asset — per rule, the author's choice.
  During early rollout the preferred experience is inline-for-short-rules in a single file; this
  is the main advantage the hybrid format buys over a mandatory file-per-rule scheme.
* **Zero-escaping content authoring.** Rules contain multi-line freeform text, raw Markdown, and
  code snippets (e.g. Java blocks). The format must hold these natively, without manual escaping.
* **Unambiguous structure and a validatable, correct file.** Because the UI is the primary
  author and the engine consumes the result, the format must have explicit, unambiguous rule
  boundaries and must be machine-validatable for correctness. Convention-based parsing that can
  misread a rule body is not acceptable.
* **Polyglot, multi-file capable.** A single project enables rules for several languages at once.
  The storage must hold rules for all of them, let the engine filter by language, and let a
  registry that grows unwieldy be split across multiple files — without that split being a format
  change or a migration event.
* **Graded correctness, not just pass/fail.** Hand-editing is a transitional concern (common
  during rollout, rare once the plugin matures), so the engine must tolerate an imperfect file
  during that window: some problems are warnings it proceeds past, some are errors it refuses,
  with an opt-in strict mode that hardens the former into the latter.

---

## 2. The Selected Solution: Hybrid XML Architecture

The proposed architecture is a **Hybrid XML Storage Strategy**. The rule registry is a
**directory of XML files**, each holding `<rule>` elements that store configuration state, rule
metadata, and rule content. One file is the common case; the directory is the unit. It operates
polymorphically per rule:

* **Inline Rules:** Shorter rules — or rules requiring immediate locality — hold their text and
  multi-line sample fragments directly inside a structured tag wrapped in a `<![CDATA[ ... ]]>`
  block.
* **External Rules:** Voluminous rules point to an external, pure Markdown (`.md`) asset via a
  path attribute.

This is selected for two reasons that hold under the goals above:

* **Unambiguous structure.** Explicit `<rule>...</rule>` boundaries mean a rule's extent is
  defined by the markup, not by a textual convention. A rule body can contain anything —
  including Markdown headings or code — without risk of being misread as a new rule.
* **Validatable correctness.** A structured XML document can be validated up front, so a
  malformed or inconsistent file (duplicate ids, a rule that is neither inline nor external,
  missing required fields) is caught before the engine loads it. With the UI as the primary
  writer, this is the property that keeps the file correct over time.

### Structural Reference Blueprint

````xml
<?xml version="1.0" encoding="UTF-8"?>
<rules enabled="true">
    <rule id="java-if-nesting" language="java" enabled="true">
        <name>if nesting</name>
        <content><![CDATA[
Never more than 2 levels of nesting in a single method. Extract third level to separate method with a descriptive name.

Bad:
```java
if (user.isActive()) {
    if (user.hasPermission()) {
        if (project.isArchived()) { // 3 levels deep!
            // logic
        }
    }
}
```
        ]]></content>
    </rule>

    <rule id="java-srp" language="java" enabled="true">
        <name>Single Responsibility Principle</name>
        <source type="external" file_ref="./rules/java/srp.md"/>
    </rule>

    <rule id="ts-no-any" language="typescript" enabled="false">
        <name>No implicit any</name>
        <content><![CDATA[ Avoid `any`; prefer `unknown` and narrow. ]]></content>
    </rule>

    <rule id="naming-descriptive" language="any" enabled="true">
        <name>Descriptive names</name>
        <content><![CDATA[ Names reveal intent; no single-letter identifiers outside loops. ]]></content>
    </rule>
</rules>
````

A rule is inline when it carries a `<content>` block, and external when it points at a file —
the two are mutually exclusive per rule. (The exact schema and validation rules are out of scope
for this proposal, which evaluates the storage *format*.)

The blueprint shows the four attributes that carry behaviour:

* **`id`** — human-readable and hand-tweakable, with a collision-resistant value auto-suggested
  by the UI on creation (slugify the name; suffix if the slug already exists in the directory).
  The XML is the **source of truth** for ids; the UI projects them and persists none of its own,
  so renaming an id stays a free edit (see §6).
* **`language`** — the filter dimension. The engine emits only rules whose language matches the
  file under refinement; the UI groups by this attribute to render one tab per language. A
  cross-cutting rule that applies to every language uses `language="any"` (a multi-value list is
  an equivalent option deferred to implementation).
* **`enabled` (on `<rule>`)** — the per-rule toggle, the primary UI path.
* **`enabled` (on `<rules>`)** — disables the whole file at once (see §4.2).

---

## 3. Performance and Memory Notes

All access is through the Java CLI, and the rules file is small (a configuration payload of
roughly tens to a few hundred KB) and is read once per CLI invocation. Parse cost is therefore
not a differentiator between candidate formats — any of them parses such a file effectively
instantly within a single CLI lifecycle.

For reads, Java handles XML natively, so no additional XML stack beyond the standard library is
required to load the file into plain Java objects during the prompt construction cycle.

For the primary path — a UI toggle of a single rule's `enabled` state — the operation changes
one attribute on one rule. This can be applied as a targeted edit to that rule's element rather
than a full parse-and-reserialize of the document, which keeps the rest of the file (including
hand-authored inline content) byte-stable across toggles. This matters specifically because
Goal 2 allows hand-editing: a toggle from the UI should not reformat a rule a human inlined.

### 3.1 Interactive UI access (tab browsing)

The §3 read-cost reasoning above covers the *engine's* path — one read per refinement run. A
second access pattern is **interactive UI browsing**: a user clicking between per-language tabs in
Cursor / VSCode or a webapp, where reads could occur repeatedly. Even split across several files —
say 5–6 files of ~20 rules each — the directory is still the same tens-to-a-few-hundred KB, which
Java's native XML parser handles in low single-digit milliseconds; the directory glob adds
nothing meaningful. So a worst-case "re-scan every file on every tab switch" would still sit well
under the threshold of perceptible UI latency. The cost that would actually be *felt* is not file
scanning but the **CLI process spawn** (JVM/jlink startup) incurred each time the extension
invokes `shipsmooth` — tens of ms per spawn, dwarfing the parse.

That cost is avoided by the §1 projection model: the extension calls the CLI **once** to obtain
the grouped-by-language projection, caches it, and renders tab switches purely in memory. Tab
browsing therefore costs **zero** file scans and zero spawns, independent of how many rule files
the directory has grown to. The "is scanning many files a perf hit" concern does not arise,
because browsing never re-scans.

This caching is safe under one stated **assumption: nothing mutates the rules directory behind
the CLI's back** — no external editor, tool, or `git checkout` rewriting the files while the UI
holds a cached projection. Under that assumption the only writer is the CLI itself, driven by the
extension, so the cache cannot go stale from the outside and no staleness-detection machinery
(modified-since checks, file watchers) is required for reads.

The assumption does **not**, however, cover **concurrent writers through the CLI** — two extension
windows on the same project, or a webapp tab and a Cursor window both open, each holding its own
cached projection. Here one window's toggle silently makes the other's cache stale. This residual
case is handled with **optimistic concurrency on the write path only**: the cached projection
carries a version stamp (a directory stamp of `*.xml` filenames plus each file's mtime and size,
or a content hash), and a toggle is sent as *"apply this change, expected-state = `<stamp>`"*. The
CLI re-stamps the directory before writing; on mismatch it rejects the write and returns the fresh
projection for the extension to reconcile, rather than clobbering a concurrent change. This needs
no locking and no watching, and it folds into the single spawn already paid for the write — and
into the byte-stable targeted edit above (check stamp → flip one attribute → done). The CLI
validate path implied by §4.3 is the natural home for computing this stamp without running a
prompt cycle.

Net: reads (browsing) are load-once-then-cached with no re-scan; only writes (toggles) pay a
concurrency check, and only at the moment they would actually conflict.

---

## 4. Polyglot Projects, Splitting, and Whole-File Disable

### 4.1 Multi-language and the registry directory

A polyglot project enables rules for several languages at once. These live as a flat set of
`<rule>` elements, each carrying a `language` attribute, with no per-language container element.
The engine filters by `language` (and `enabled`) when building a prompt; the UI projects the same
set grouped by `language` into one tab per language. Adding a language is therefore not a
structural change — it is more rules with a different `language` value.

The registry is a **directory of XML files, not a single file**. The engine loads every `<rule>`
found under the rules directory (a glob over the directory's `*.xml`); one file is simply the
smallest, most common case. Two consequences are load-bearing and are fixed from day one so that
later growth is a non-event:

* **Splitting is not a format feature.** When a single file grows unwieldy, a human moves some
  `<rule>` elements into a second file in the same directory. There is no include/import element,
  no file-to-file reference, no cross-file machinery — the loader already globs the directory, so
  the split file is picked up with no change to the engine, CLI, or UI. The single-file case is
  the degenerate one-file glob.
* **Identity spans the directory.** A rule's `id` is unique across the whole directory, not
  merely within its file. Duplicate-id checking, UI grouping, and engine filtering all operate on
  the union of `*.xml`. Baking this in now is what keeps "split the file later" free.

Note that **external rules are the primary relief for an unwieldy file**, and physical file
splitting is the secondary backstop. Under the rollout discipline of inline-only-for-short-rules,
a rule large enough to bloat the file is exactly one that would be made external — its bulk moves
to a Markdown asset and the registry keeps a one-line stub. Reaching a file size that genuinely
warrants splitting therefore requires a large *count* of rules, not large rules.

### 4.2 Whole-file disable

A file is disabled with a single top-level attribute, `<rules enabled="false">`. This is the
file-level analogue of the per-rule `enabled` flag and is preferred over encoding state in the
filename (e.g. `disabled-foo.xml`), which was considered and rejected: a filename prefix moves
state out of the validatable document into a parsing convention — the same fragility that
disqualified header-delimited Markdown (§7.1) — it collides with the directory glob, and it turns
a toggle into a filesystem rename rather than the byte-stable in-place edit of §3.

Disable is **hard, not a default**: a disabled file disables every rule inside it regardless of
each rule's own flag. The precedence is one line —

> a rule is emitted only if its file is enabled **and** the rule is enabled
> (`effective = file.enabled && rule.enabled`); absent attributes default to `true`.

A disabled file is treated as **not part of the system for now**: its rules are not emitted and
take no part in cross-file concerns — in particular they do not participate in the directory-wide
`id` namespace, so a disabled file's ids cannot clash with an enabled file's. Re-enabling a file
is therefore a validating operation that can newly surface a clash (see §4.3).

### 4.3 Graded validation

Validation is graded, not binary. Three severities, and an opt-in `strict` mode:

* **Malformed XML.** In an *enabled* file this is an **error** — the engine refuses to load.
  In a *disabled* file it is a **warning** only (surfaced in the UI's warnings view, file
  ignored); the broken file cannot break the system because it is not part of it.
* **Duplicate `id` across the directory (enabled files).** A **warning** by default, resolved by
  a precedence rule, escalating to an **error** under `strict`. Because clashes are rare —
  UI-created ids are auto-suggested collision-resistantly, so clashes arise mainly from
  hand-edit copy-paste — strict mode forces the user to disambiguate, typically by adding a
  suffix to one id. (The exact default precedence is deferred; the requirement is only that it be
  predictable to a human and not depend on filesystem glob order.)
* **Other structural problems** (dangling `file_ref`, a rule that is neither inline nor external,
  missing required fields) are warnings by default and errors under `strict`.

A disabled file's problems are **always warnings, never errors, even under `strict`** — strict
mode hardens enabled-file checks only, consistent with §4.2's "not part of the system". Surfacing
warnings implies a CLI validate path the extension can call to populate a per-file / per-tab
warnings view without running a prompt cycle. This same validate path is the natural place to
compute the directory stamp used for the write-path optimistic-concurrency check of §3.1: a
stale-stamp toggle is rejected and surfaces as a **conflict** the extension reconciles against the
returned fresh projection — distinct from the structural severities above, since it concerns
concurrent writers rather than file correctness.

---

## 5. Architectural Evaluation: Pros and Cons

### Pros
* **Unambiguous rule boundaries.** `<rule>...</rule>` delimits each rule explicitly, so rule
  bodies can contain arbitrary Markdown and code without being misparsed.
* **Validatable correctness.** The document can be checked for structural and semantic
  correctness before use — the property that keeps a UI-authored file trustworthy over time.
* **Native CDATA multi-line isolation.** `<![CDATA[ ... ]]>` holds raw Markdown and code
  snippets verbatim, so authors can paste complex content inline without escaping.
* **Clean inline/external polymorphism.** A single layout supports both fully-inline rules and
  rules extracted to external files, decided per rule, satisfying both goals.
* **Polyglot without restructuring.** A `language` attribute plus directory-wide identity lets one
  registry hold many languages, filter per language, and split across files later — all without a
  format change or migration (§4).

### Cons
* **Hand-editing is heavier than plain Markdown.** Tag balancing and the CDATA wrapper make a
  by-hand edit more cumbersome than editing a Markdown section. This is an accepted trade-off:
  hand-editing is the secondary path (Goal 2), and the UI is the primary author (Goal 1).
* **CDATA literal restriction.** The literal sequence `]]>` cannot appear inside a CDATA block,
  so a rule body containing it requires the standard CDATA-splitting workaround.
* **Structural verbosity.** Tag markup adds character overhead on disk compared to flatter,
  minimal syntaxes.

---

## 6. Deferred Decision: ID Storage and Tweakability

The starting position is **human-tweakable ids with a UI-auto-suggested default**, and the XML as
the sole **source of truth** — the UI is a pure projection that persists no id state of its own.
Under that model, tweaking an id is a free edit: nothing else references the old value, so the
next projection simply shows the new one.

This freedom has one explicit boundary worth marking for future work. The day an id is stored
**outside** the XML — UI state, a cache, per-user enabled-sets keyed by id, analytics — that store
becomes a second reference, and tweaking an id becomes a *rename* requiring re-binding (the same
class of concern as the rejected filename-disable). Until such external storage is introduced,
ids remain freely tweakable; introducing it is the moment to revisit "tweakable". Whether to store
ids elsewhere is left open.

---

## 7. Alternatives Considered

### 7.1 Header-delimited Markdown (single file, many rules)

A single Markdown file where each rule is a section (e.g. `## [x] <id> (<language>)`), the body
is the raw Markdown beneath it, an `[x]`/`[ ]` marker encodes enabled state, and an optional
`-> path.md` suffix marks an external body. This keeps everything in one hand-editable file,
allows inline rules by default, and makes a toggle a one-character edit on the header line.

**Rejected** for the requirement of unambiguous structure and a validatable file. Rule
boundaries are defined by a textual convention rather than markup: a line beginning with `## `
inside a rule body (for example in an embedded code or documentation sample) can be mistaken for
the start of a new rule, and the format has no inherent structural contract to validate against.
Given that the UI is the primary author and correctness is a stated goal, convention-based
parsing was judged too fragile, despite Markdown's superior hand-editing ergonomics.

### 7.2 Hybrid JSON Configuration Model

A JSON file with the same inline/external split, parsed via a standard JSON library. JSON is a
familiar, widely-tooled configuration format.

**Rejected** primarily on the zero-escaping requirement:

1. **String escaping friction.** JSON has no native multi-line block-scalar syntax. Keeping a
   rule inline forces every newline to be escaped as `\n` and every quote as `\"`. This breaks
   the requirement to author and edit inline rule bodies (multi-line Markdown and code) without
   escaping.
2. **Mutation fragility for inline content.** Editing a single rule whose body is a long escaped
   string is error-prone by hand and offers none of XML's explicit per-rule boundaries.
3. **Loss of inline formatting locality.** The escaping burden pushes authors toward external
   files even for trivial rules, working against the goal that inline must remain a first-class,
   low-friction option.

JSON's native fit with browser/extension JavaScript runtimes (`JSON.parse()`) was noted during
evaluation but is not relevant here: the access path is the Java CLI, not JavaScript, so this
offers no advantage for ShipSmooth.
