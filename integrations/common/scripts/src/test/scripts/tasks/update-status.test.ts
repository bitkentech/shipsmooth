import { test } from 'node:test';
import assert from 'node:assert/strict';
import { updateTaskStatus } from '../../../main/scripts/tasks/update-status';

const BASE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>13</plan>
  <plan-version>plan-13-v1</plan-version>
  <metadata>
    <backlog-issue>PB-221</backlog-issue>
    <status>active</status>
    <created>2026-04-10</created>
  </metadata>

  <tasks>
  <task>
    <id>1</id>
    <risk>high</risk>
    <status>pending</status>
    <name>Task one</name>
    <commit></commit>
    <created-from>plan-13-v1</created-from>
    <closed-at-version></closed-at-version>
    <comments></comments>
    <deviations></deviations>
  </task>
  <task>
    <id>2</id>
    <risk>low</risk>
    <status>pending</status>
    <name>Task two</name>
    <commit></commit>
    <created-from>plan-13-v1</created-from>
    <closed-at-version></closed-at-version>
    <comments></comments>
    <deviations></deviations>
  </task>
  </tasks>

  <project-updates>
    <update>
      <timestamp>2026-04-10T10:00:00.000Z</timestamp>
      <message>Plan initialised.</message>
      <blocked>false</blocked>
    </update>
  </project-updates>
</plan-tasks>
`;

test('updateTaskStatus sets status on correct task', () => {
  const result = updateTaskStatus(BASE_XML, 1, 'in-progress', '');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task1 = tasks.find(m => m[1].includes('<id>1</id>'))?.[1] ?? '';
  assert.match(task1, /<status>in-progress<\/status>/);
});

test('updateTaskStatus does not affect other tasks', () => {
  const result = updateTaskStatus(BASE_XML, 1, 'in-progress', '');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task2 = tasks.find(m => m[1].includes('<id>2</id>'))?.[1] ?? '';
  assert.match(task2, /<status>pending<\/status>/);
});

test('updateTaskStatus sets closed-at-version when status is closed', () => {
  const result = updateTaskStatus(BASE_XML, 1, 'closed', 'plan-13-v2');
  assert.match(result, /<closed-at-version>plan-13-v2<\/closed-at-version>/);
});

test('updateTaskStatus leaves closed-at-version empty for non-closed status', () => {
  const result = updateTaskStatus(BASE_XML, 1, 'agent-coded', '');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task1 = tasks.find(m => m[1].includes('<id>1</id>'))?.[1] ?? '';
  assert.match(task1, /<closed-at-version><\/closed-at-version>/);
});

test('updateTaskStatus throws on unknown task id', () => {
  assert.throws(
    () => updateTaskStatus(BASE_XML, 99, 'in-progress', ''),
    /task 99 not found/i
  );
});

test('updateTaskStatus throws on invalid status', () => {
  assert.throws(
    () => updateTaskStatus(BASE_XML, 1, 'invalid-status' as never, ''),
    /invalid status/i
  );
});
