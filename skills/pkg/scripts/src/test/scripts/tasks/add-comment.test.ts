import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addComment } from '../../../main/scripts/tasks/add-comment';
import { addDeviation } from '../../../main/scripts/tasks/add-deviation';

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

// --- addComment ---

test('addComment inserts comment into correct task', () => {
  const result = addComment(BASE_XML, 1, 'De-risk validated', '2026-04-10T12:00:00.000Z');
  // task 1 block: between first <task> and its </task>
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task1 = tasks.find(m => m[1].includes('<id>1</id>'))?.[1] ?? '';
  assert.match(task1, /<timestamp>2026-04-10T12:00:00.000Z<\/timestamp>/);
  assert.match(task1, /<message>De-risk validated<\/message>/);
});

test('addComment does not affect other tasks', () => {
  const result = addComment(BASE_XML, 1, 'some comment', '2026-04-10T12:00:00.000Z');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task2 = tasks.find(m => m[1].includes('<id>2</id>'))?.[1] ?? '';
  assert.doesNotMatch(task2, /<comment>/);
});

test('addComment escapes XML special chars in message', () => {
  const result = addComment(BASE_XML, 1, 'Use <bold> & "quotes"', '2026-04-10T12:00:00.000Z');
  assert.match(result, /Use &lt;bold&gt; &amp; &quot;quotes&quot;/);
});

test('addComment throws on unknown task id', () => {
  assert.throws(() => addComment(BASE_XML, 99, 'msg', '2026-04-10T12:00:00.000Z'), /task 99 not found/i);
});

// --- addDeviation ---

test('addDeviation inserts deviation into correct task', () => {
  const result = addDeviation(BASE_XML, 2, 'minor', 'Split into two parts', '2026-04-10T13:00:00.000Z');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task2 = tasks.find(m => m[1].includes('<id>2</id>'))?.[1] ?? '';
  assert.match(task2, /<type>minor<\/type>/);
  assert.match(task2, /<timestamp>2026-04-10T13:00:00.000Z<\/timestamp>/);
  assert.match(task2, /<message>Split into two parts<\/message>/);
});

test('addDeviation does not affect other tasks', () => {
  const result = addDeviation(BASE_XML, 2, 'minor', 'Split', '2026-04-10T13:00:00.000Z');
  const tasks = [...result.matchAll(/<task>([\s\S]*?)<\/task>/g)];
  const task1 = tasks.find(m => m[1].includes('<id>1</id>'))?.[1] ?? '';
  assert.doesNotMatch(task1, /<deviation>/);
});

test('addDeviation throws on unknown task id', () => {
  assert.throws(() => addDeviation(BASE_XML, 99, 'minor', 'msg', '2026-04-10T13:00:00.000Z'), /task 99 not found/i);
});

test('addDeviation throws on invalid type', () => {
  assert.throws(() => addDeviation(BASE_XML, 1, 'invalid' as never, 'msg', '2026-04-10T13:00:00.000Z'), /invalid type/i);
});
