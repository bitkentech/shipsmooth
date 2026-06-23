# Plan 22 — Refactor task XML scripts to use fast-xml-parser

## Context

The five task-tracking mutation scripts (`update-status.ts`, `add-comment.ts`, `add-deviation.ts`, `set-commit.ts`, `project-update.ts`) manipulate `.agents/plans/plan-{N}-tasks.xml` using regex string replacement. The logic is fragile — `add-comment.ts` and `add-deviation.ts` use complex multiline nested patterns that are sensitive to whitespace and structure variation. Silent wrong-match bugs are possible.

`fast-xml-parser` is already a declared dependency in `package.json` (version `^5.0.0`). The approach: introduce a shared `xml-utils.ts` module that owns parse/serialize, then rewrite each script's core logic to operate on the parsed object tree.

**Permanent backlog feature issue:** PB-271

### Key facts from code reading

- All scripts follow the same pattern: `readFileSync` → transform string → `writeFileSync`
- `types.ts` already defines `PlanTasks`, `PlanTask`, `Comment`, `Deviation`, `ProjectUpdate`, `PlanMetadata` — the target DOM shape is already typed
- `fast-xml-parser` v5: `XMLParser` for parsing, `XMLBuilder` for serializing
- XML attributes (`id`, `risk`, `status`, `type`, `timestamp`, `blocked`) need `ignoreAttributes: false` on the parser
- The serializer must preserve attribute order and not collapse empty elements (e.g. `<commit></commit>` not `<commit/>`) — XMLBuilder `suppressEmptyNode: false`
- Existing tests in `src/test/scripts/tasks/` test the exported functions against XML strings — they will continue to work unchanged since the function signatures don't change
- `init.ts` generates XML via string templates (not fast-xml-parser) — leave it alone; it only writes, never mutates

### Resolved decisions

- **Shared `xml-utils.ts`**: `parsePlanXml(xml: string): PlanTasks` and `serializePlanXml(plan: PlanTasks): string`. All mutation scripts import from here.
- **Preserve `<comments></comments>` and `<deviations></deviations>` as arrays**: fast-xml-parser collapses single-element arrays by default — use `isArray` option to force `comment` and `deviation` to always be arrays.
- **Attribute parsing**: `ignoreAttributes: false`, `attributeNamePrefix: '@_'` (fast-xml-parser v5 default).
- **Whitespace/formatting**: accept that serialized output may differ slightly from hand-edited XML (different indentation). Tests assert on content, not whitespace — this is fine.
- **`init.ts` untouched**: it generates XML from scratch via string templates. No regression risk.
- **Task tracking:** `[Local]` XML at `.agents/plans/plan-22-tasks.xml`.
- **Coverage threshold:** 95% where tests exist. All existing tests must pass. New tests for `xml-utils.ts` parse/serialize round-trip.

---

## Tasks (risk-sorted)

### Task 1: Introduce xml-utils.ts with parse/serialize and update tests [High]

Create `plugin-node/src/main/scripts/tasks/xml-utils.ts` with:
- `parsePlanXml(xml: string): PlanTasks` — maps fast-xml-parser output to `PlanTasks` type
- `serializePlanXml(plan: PlanTasks): string` — maps `PlanTasks` back to XML string

Parser config:
```ts
new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
  isArray: (name) => ['task', 'comment', 'deviation', 'update'].includes(name),
  allowBooleanAttributes: true,
})
```

Serializer config:
```ts
new XMLBuilder({
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
  suppressEmptyNode: false,
  format: true,
  indentBy: '  ',
})
```

Write tests in `src/test/scripts/tasks/xml-utils.test.ts` covering:
- Round-trip: `serializePlanXml(parsePlanXml(xml))` preserves all field values
- Tasks array always present even if empty
- Comments/deviations always arrays even with single element

### Task 2: Rewrite update-status.ts and set-commit.ts [Medium]

Both scripts do simple single-field mutations on a `<task>` node.

`updateTaskStatus`: find task by `@_id`, set `@_status`, optionally set `closed-at-version`.
`setCommit`: find task by `@_id`, set `commit` text content.

Existing tests must pass unchanged.

### Task 3: Rewrite add-comment.ts and add-deviation.ts [Medium]

These have the most complex regex today (nested multiline patterns). With fast-xml-parser:
- Parse → find task by id → push to `comments` or `deviations` array → serialize.

Both scripts currently escape XML special chars in the message. With fast-xml-parser, the builder handles escaping automatically — remove manual `escapeXml()` calls from these scripts (keep it in `project-update.ts` only if still needed).

Existing tests must pass unchanged.

### Task 4: Rewrite project-update.ts [Low]

Appends to `<project-updates>` and optionally updates `<metadata><status>`. Straightforward with DOM access.

Existing tests must pass unchanged.

---

## Verification

```bash
cd plugin-node
npm test
```

All existing tests green. New `xml-utils.test.ts` tests green. Coverage ≥ 95%.

Then rebuild and smoke-test against a real plan XML:
```bash
mvn process-resources -q
node build/scripts/tasks/update-status.js --plan 22 --task 1 --status in-progress
node build/scripts/tasks/add-comment.js --plan 22 --task 1 --message "test comment"
cat .agents/plans/plan-22-tasks.xml
```
