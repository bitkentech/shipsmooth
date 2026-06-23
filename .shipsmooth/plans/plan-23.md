# Plan 23 — Define XSD for task XML files

## Context

The plan task XML files (`.agents/plans/plan-{N}-tasks.xml`) have no formal schema. The structure is implicitly defined by TypeScript types in `types.ts` and the string template in `init.ts`. PB-274 calls for an XSD that formally documents the current format, so the schema is explicit, versionable, and can guide future migrations (PB-275: attributes→elements).

**Permanent backlog feature issue:** PB-274
**Task tracking:** `[Local]` XML at `.agents/plans/plan-23-tasks.xml`

### Key facts from code reading

- 6 existing XML files (plan-17 through plan-22) define the implicit schema
- Root `<plan-tasks>` has attributes: `plan` (integer), `plan-version` (string pattern `plan-\d+-v\d+`)
- `<metadata>` contains: `<backlog-issue>`, `<status>` (enum: active|complete|abandoned|in-review), `<created>` (ISO date)
- `<task>` has attributes: `id` (integer), `risk` (enum: high|medium|low|empty string), `status` (7-value enum)
- `<task>` children: `<name>`, `<commit>`, `<created-from>`, `<closed-at-version>`, `<comments>`, `<deviations>`
- `<comment>` has `timestamp` attribute + text content; `<deviation>` has `type` and `timestamp` attributes + text content
- `<update>` has `timestamp` attribute, optional `blocked` boolean attribute, + text content
- `risk=""` (empty string) is used in real data — XSD needs `xs:union` of enum + zero-length string
- `<comments>` and `<deviations>` can be empty containers or hold 0..N children
- `xmllint` is available at `/usr/bin/xmllint` with Schemas support (libxml version 20914)

### Resolved decisions

- **XSD location:** `plugin-node/src/main/scripts/tasks/plan-tasks.xsd` — co-located with xml-utils.ts and types.ts
- **Validation approach:** `xmllint --schema` via CLI for now. No runtime integration into parsePlanXml() in this plan — that's a separate concern
- **Scope:** XSD captures the CURRENT attribute-based format exactly. Attribute→element migration is PB-275
- **Coverage threshold:** 95% where tests exist
- **xs:any extension points:** `<task>` and `<metadata>` get `xs:any` at the end of their sequence. `processContents="lax"`, `minOccurs="0"`, `maxOccurs="unbounded"`. These two elements are most likely to grow (task-level fields like estimate/labels, plan-level config). Containers and text-content elements are stable and don't need it.

---

## Tasks (risk-sorted)

### Task 1: Write the XSD and validate against all existing XML files [Medium]

Create `plugin-node/src/main/scripts/tasks/plan-tasks.xsd` capturing the current format exactly.

Key XSD modeling decisions:
- `<plan-tasks>` root with `plan` (xs:positiveInteger) and `plan-version` (xs:string, pattern `plan-\d+-v\d+`) attributes
- `risk` attribute: `xs:union` of enum `{high, medium, low}` and zero-length string (to allow `risk=""`)
- `status` attribute on `<task>`: enum of 7 values
- `status` element in `<metadata>`: enum of 4 values
- `<comment>`, `<deviation>`, `<update>`: complexType with simpleContent extension (attributes + text)
- `blocked` attribute on `<update>`: optional xs:boolean
- `<comments>` and `<deviations>` containers: allow empty or 0..N children
- Timestamp attributes: xs:dateTime

Validate with `xmllint --schema plan-tasks.xsd --noout <file>` against all 6 existing files (plan-17 through plan-22).

### Task 2: Add XSD validation test [Medium]

Add a test that runs `xmllint --schema` against each existing plan XML file programmatically, and also tests that deliberately invalid XML fails validation.

**Files:** `plugin-node/src/test/scripts/tasks/xsd-validation.test.ts` (new)

### Task 3: Add xs:any extension points to TaskType and MetadataType [Low]

Add `xs:any` at the end of the `xs:sequence` in both `TaskType` and `MetadataType`:

```xml
<xs:any namespace="##any" processContents="lax" minOccurs="0" maxOccurs="unbounded"/>
```

- `MetadataType` sequence: after `<created>`
- `TaskType` sequence: after `<deviations>`

Add a test confirming that XML with unknown extension elements in `<metadata>` and `<task>` passes validation. All existing XML files must still validate.

**Files:** `plugin-node/src/main/scripts/tasks/plan-tasks.xsd`, `plugin-node/src/test/scripts/tasks/xsd-validation.test.ts`

---

## Verification

```bash
# Validate all existing XML files against the XSD
for f in .agents/plans/plan-*-tasks.xml; do
  xmllint --schema plugin-node/src/main/scripts/tasks/plan-tasks.xsd --noout "$f"
done

# Run tests
cd plugin-node && npm test
```
