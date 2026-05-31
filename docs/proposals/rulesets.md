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
single skill template (`refine/SKILL.jte.md`). To deploy across varied codebases and integrate
with developer IDEs, the engine needs a configuration subsystem that breaks this monolith into
individual, selectively toggled rules.

All access to the rules file — both reads and writes — goes through the Java CLI (the jlink
image). The Cursor / VSCode extension does not parse or rewrite the file itself; it invokes the
existing `shipsmooth` command, which performs the read or the toggle. There is no JavaScript in
the access path.

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
* **Zero-escaping content authoring.** Rules contain multi-line freeform text, raw Markdown, and
  code snippets (e.g. Java blocks). The format must hold these natively, without manual escaping.
* **Unambiguous structure and a validatable, correct file.** Because the UI is the primary
  author and the engine consumes the result, the format must have explicit, unambiguous rule
  boundaries and must be machine-validatable for correctness. Convention-based parsing that can
  misread a rule body is not acceptable.

---

## 2. The Selected Solution: Hybrid XML Architecture

The proposed architecture is a **Hybrid XML Storage Strategy**. A centralized XML file functions
as a structured rule registry, storing configuration state, rule metadata, and rule content. It
operates polymorphically per rule:

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

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rules>
    <rule id="550e8400-e29b-41d4-a716-446655440001" language="java" enabled="true">
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

    <rule id="550e8400-e29b-41d4-a716-446655440002" language="java" enabled="true">
        <name>Single Responsibility Principle</name>
        <source type="external" file_ref="./rules/java/srp.md"/>
    </rule>
</rules>
```

A rule is inline when it carries a `<content>` block, and external when it points at a file —
the two are mutually exclusive per rule. (The exact schema and validation rules are out of scope
for this proposal, which evaluates the storage *format*.)

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

---

## 4. Architectural Evaluation: Pros and Cons

### Pros
* **Unambiguous rule boundaries.** `<rule>...</rule>` delimits each rule explicitly, so rule
  bodies can contain arbitrary Markdown and code without being misparsed.
* **Validatable correctness.** The document can be checked for structural and semantic
  correctness before use — the property that keeps a UI-authored file trustworthy over time.
* **Native CDATA multi-line isolation.** `<![CDATA[ ... ]]>` holds raw Markdown and code
  snippets verbatim, so authors can paste complex content inline without escaping.
* **Clean inline/external polymorphism.** A single layout supports both fully-inline rules and
  rules extracted to external files, decided per rule, satisfying both goals.

### Cons
* **Hand-editing is heavier than plain Markdown.** Tag balancing and the CDATA wrapper make a
  by-hand edit more cumbersome than editing a Markdown section. This is an accepted trade-off:
  hand-editing is the secondary path (Goal 2), and the UI is the primary author (Goal 1).
* **CDATA literal restriction.** The literal sequence `]]>` cannot appear inside a CDATA block,
  so a rule body containing it requires the standard CDATA-splitting workaround.
* **Structural verbosity.** Tag markup adds character overhead on disk compared to flatter,
  minimal syntaxes.

---

## 5. Alternatives Considered

### 5.1 Header-delimited Markdown (single file, many rules)

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

### 5.2 Hybrid JSON Configuration Model

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
