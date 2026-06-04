import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseTasksFromPlan, generateXml } from '../../../main/scripts/tasks/init';
import { parsePlanXml } from '../../../main/scripts/tasks/xml-utils';

// Minimal plan markdown fixture
const PLAN_MD = `
# Plan 99 · Test plan

## Context
Some context here.

## Tasks (risk-sorted: High → Medium → Low)

### Task 1: SessionStart hook — install deps [High]
- Add package.json
- Add tsconfig.json

### Task 2: Implement init.ts [Medium]
- Parse plan markdown
- Generate XML

### Task 3: Update README.md [Low]
- Add a sentence
`;

test('parseTasksFromPlan extracts all tasks', () => {
  const tasks = parseTasksFromPlan(PLAN_MD);
  assert.equal(tasks.length, 3);
});

test('parseTasksFromPlan captures task ids in order', () => {
  const tasks = parseTasksFromPlan(PLAN_MD);
  assert.deepEqual(tasks.map((t: { id: number }) => t.id), [1, 2, 3]);
});

test('parseTasksFromPlan captures task names', () => {
  const tasks = parseTasksFromPlan(PLAN_MD);
  assert.equal(tasks[0].name, 'SessionStart hook — install deps');
  assert.equal(tasks[1].name, 'Implement init.ts');
  assert.equal(tasks[2].name, 'Update README.md');
});

test('parseTasksFromPlan captures risk levels (lowercase)', () => {
  const tasks = parseTasksFromPlan(PLAN_MD);
  assert.equal(tasks[0].risk, 'high');
  assert.equal(tasks[1].risk, 'medium');
  assert.equal(tasks[2].risk, 'low');
});

test('parseTasksFromPlan defaults risk to unset when not specified', () => {
  const md = `## Tasks\n\n### Task 1: No risk label here\n- bullet\n`;
  const tasks = parseTasksFromPlan(md);
  assert.equal(tasks[0].risk, '');
});

test('generateXml round-trip: output parses cleanly via parsePlanXml', () => {
  const tasks = parseTasksFromPlan(PLAN_MD);
  const xml = generateXml(99, 'plan-99-v1', tasks);
  const parsed = parsePlanXml(xml);
  assert.strictEqual(parsed.planNum, 99);
  assert.strictEqual(parsed.planVersion, 'plan-99-v1');
  assert.strictEqual(parsed.tasks.length, 3);
  assert.strictEqual(parsed.tasks[0].id, 1);
  assert.strictEqual(parsed.tasks[0].risk, 'high');
  assert.strictEqual(parsed.tasks[0].status, 'pending');
  assert.strictEqual(parsed.tasks[0].name, 'SessionStart hook — install deps');
});
