# Plan 24 — Migrate XML attributes to elements (PB-275)

## Context

The task XML files use XML attributes (`id`, `risk`, `status`, `timestamp`, `type`, `blocked`, `plan`, `plan-version`) for structured data. Moving these to child elements simplifies the relational mapping (PB-273/AlaSQL), removes the `@_` prefix gymnastics in xml-utils.ts, and makes the XML more readable. PB-274 (XSD) is already done and provides the migration target specification.

**Permanent backlog feature issue:** PB-275
**Task tracking:** `[Local]` XML at `.agents/plans/plan-24-tasks.xml`

### Key facts from code reading

**Attributes to migrate (complete list):**
| Element | Attributes | New child elements |
|---------|-----------|-------------------|
| `<plan-tasks>` | `plan`, `plan-version` | `<plan>`, `<plan-version>` |
| `<task>` | `id`, `risk`, `status` | `<id>`, `<risk>`, `<status>` |
| `<comment>` | `timestamp` + `#text` body | `<timestamp>`, `<message>` |
| `<deviation>` | `type`, `timestamp` + `#text` body | `<type>`, `<timestamp>`, `<message>` |
| `<update>` | `timestamp`, `blocked` + `#text` body | `<timestamp>`, `<message>`, `<blocked>` |

**Files that need changes:**
- `plugin-node/src/main/scripts/tasks/plan-tasks.xsd` — rewrite all complexType definitions
- `plugin-node/src/main/scripts/tasks/xml-utils.ts` — rewrite parse + serialize (all `@_` and `#text` access)
- `plugin-node/src/main/scripts/tasks/init.ts` — rewrite string template (uses string interpolation, not xml-utils)
- `plugin-node/src/main/scripts/tasks/show.ts` — rewrite `parseAttr()` calls to `parseElement()` (does NOT use xml-utils; raw regex)
- All 8 test files — update inline XML fixtures + regex assertions
- All existing `.agents/plans/plan-*-tasks.xml` files — migrate with a conversion script

**Zero-change files:** `update-status.ts`, `set-commit.ts`, `add-comment.ts`, `add-deviation.ts`, `project-update.ts`

**Zero-change:** `types.ts` — already uses camelCase property names, agnostic to XML structure

### Resolved decisions

- **XSD update strategy:** Rewrite in-place. No `simpleContent` extensions — all types become `xs:complexType` with `xs:sequence` children.
- **fast-xml-parser config:** Remove `ignoreAttributes`, `attributeNamePrefix`, `allowBooleanAttributes`, `suppressBooleanAttributes`. Keep `isArray` for `task`, `comment`, `deviation`, `update`.
- **`blocked` element:** Serialize as `<blocked>false</blocked>` always (explicit, simpler than omit-if-false).
- **Migration script:** `.agents/tmp/migrate-plans.js` — dual-mode: parse old format with old parser config, serialize with new `serializePlanXml()`.
- **Coverage threshold:** 95%

---

## Tasks (risk-sorted)

### Task 1: Rewrite XSD for element-only format [High]

Update `plugin-node/src/main/scripts/tasks/plan-tasks.xsd`:
- `<plan-tasks>`: remove attributes; add `<plan>` and `<plan-version>` child elements
- `<task>`: remove attributes; add `<id>`, `<risk>`, `<status>` child elements
- `<comment>`: `simpleContent` → `xs:complexType` with `<timestamp>` + `<message>`
- `<deviation>`: `simpleContent` → `xs:complexType` with `<type>`, `<timestamp>`, `<message>`
- `<update>`: `simpleContent` → `xs:complexType` with `<timestamp>`, `<message>`, `<blocked>` (minOccurs=0)
- `xs:any` extension points on `MetadataType` and `TaskType` remain

Update `xsd-validation.test.ts` fixtures to element-based format.

### Task 2: Rewrite xml-utils.ts parse/serialize [High]

Replace all `@_` and `#text` access with direct element access. Simplify parser/builder config. All `xml-utils.test.ts` fixtures and tests updated.

### Task 3: Rewrite init.ts string template [Medium]

Update `generateXml()` to emit element-based format. Add round-trip test: generated XML parses cleanly via `parsePlanXml()`.

### Task 4: Rewrite show.ts regex parsing [Medium]

Remove `parseAttr()`. Update `parseTasks()` and `parseUpdates()` to use element-based regex. Update `show.test.ts` fixture.

### Task 5: Migrate all existing plan XML files [Medium]

Write `.agents/tmp/migrate-plans.js` using old-format parser + new `serializePlanXml()`. Run against all plan XMLs. Validate all against updated XSD. Commit migrated files.

### Task 6: Update remaining test fixtures [Medium]

Update `BASE_XML` / `INTEGRATION_XML` fixtures in `add-comment.test.ts`, `set-commit.test.ts`, `update-status.test.ts`, `integration.test.ts`. Full suite (53+ tests) green.

---

## Verification

```bash
for f in .agents/plans/plan-*-tasks.xml; do
  xmllint --schema plugin-node/src/main/scripts/tasks/plan-tasks.xsd --noout "$f"
done

cd plugin-node && npm test
# Expected: 53+ tests, 0 failures
```
