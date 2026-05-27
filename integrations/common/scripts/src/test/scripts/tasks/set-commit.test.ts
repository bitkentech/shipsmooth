import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setCommit } from '../../../main/scripts/tasks/set-commit';
import { projectUpdate } from '../../../main/scripts/tasks/project-update';

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

// --- setCommit ---

test('setCommit updates commit element for correct task', () => {
  const result = setCommit(BASE_XML, 1, 'abc1234');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task1 = tasks.find(m => m[1].includes('<id>1</id>'))?.[1] ?? '';
  assert.match(task1, /<commit>abc1234<\/commit>/);
});

test('setCommit throws on unknown task id', () => {
  assert.throws(() => setCommit(BASE_XML, 99, 'abc1234'), /task 99 not found/i);
});

// --- projectUpdate ---

test('projectUpdate appends update entry', () => {
  const result = projectUpdate(BASE_XML, 'Ready for review', false, '2026-04-10T14:00:00.000Z');
  assert.match(result, /<timestamp>2026-04-10T14:00:00.000Z<\/timestamp>/);
  assert.match(result, /<message>Ready for review<\/message>/);
});

test('projectUpdate preserves existing updates', () => {
  const result = projectUpdate(BASE_XML, 'New update', false, '2026-04-10T14:00:00.000Z');
  assert.match(result, /Plan initialised\./);
  assert.match(result, /New update/);
});

test('projectUpdate sets blocked element when blocked=true', () => {
  const result = projectUpdate(BASE_XML, 'Blocked on X', true, '2026-04-10T14:00:00.000Z');
  // Find the new update block and check blocked=true
  const updates = [...result.matchAll(/<update>([\s\S]*?)<\/update>/g)];
  const blockedUpdate = updates.find(m => m[1].includes('Blocked on X'))?.[1] ?? '';
  assert.match(blockedUpdate, /<blocked>true<\/blocked>/);
});

test('projectUpdate sets blocked=false when blocked=false', () => {
  const result = projectUpdate(BASE_XML, 'All good', false, '2026-04-10T14:00:00.000Z');
  const updates = [...result.matchAll(/<update>([\s\S]*?)<\/update>/g)];
  const newUpdate = updates.find(m => m[1].includes('All good'))?.[1] ?? '';
  assert.match(newUpdate, /<blocked>false<\/blocked>/);
});

test('projectUpdate updates metadata status when provided', () => {
  const result = projectUpdate(BASE_XML, 'Done', false, '2026-04-10T14:00:00.000Z', 'complete');
  assert.match(result, /<status>complete<\/status>/);
});
