import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePlanXml, serializePlanXml } from '../../../main/scripts/tasks/xml-utils';

const SAMPLE_XML = `<?xml version="1.0" encoding="UTF-8"?>
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
    <status>agent-coded</status>
    <name>Task two</name>
    <commit>abc1234</commit>
    <created-from>plan-13-v1</created-from>
    <closed-at-version>plan-13-v2</closed-at-version>
    <comments>
      <comment>
        <timestamp>2026-04-10T12:00:00.000Z</timestamp>
        <message>First comment</message>
      </comment>
    </comments>
    <deviations>
      <deviation>
        <type>minor</type>
        <timestamp>2026-04-10T13:00:00.000Z</timestamp>
        <message>Split task</message>
      </deviation>
    </deviations>
  </task>
  </tasks>

  <project-updates>
    <update>
      <timestamp>2026-04-10T10:00:00.000Z</timestamp>
      <message>Plan initialised.</message>
    </update>
    <update>
      <timestamp>2026-04-10T14:00:00.000Z</timestamp>
      <message>Blocked on review.</message>
      <blocked>true</blocked>
    </update>
  </project-updates>
</plan-tasks>
`;

const EMPTY_COLLECTIONS_XML = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>5</plan>
  <plan-version>plan-5-v1</plan-version>
  <metadata>
    <backlog-issue>PB-100</backlog-issue>
    <status>active</status>
    <created>2026-04-01</created>
  </metadata>

  <tasks>
  <task>
    <id>1</id>
    <risk>medium</risk>
    <status>pending</status>
    <name>Only task</name>
    <commit></commit>
    <created-from>plan-5-v1</created-from>
    <closed-at-version></closed-at-version>
    <comments></comments>
    <deviations></deviations>
  </task>
  </tasks>

  <project-updates>
    <update>
      <timestamp>2026-04-01T09:00:00.000Z</timestamp>
      <message>Init.</message>
    </update>
  </project-updates>
</plan-tasks>
`;

// --- parsePlanXml ---

test('parsePlanXml reads plan number and version', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.equal(plan.planNum, 13);
  assert.equal(plan.planVersion, 'plan-13-v1');
});

test('parsePlanXml reads metadata fields', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.equal(plan.metadata.backlogIssue, 'PB-221');
  assert.equal(plan.metadata.status, 'active');
  assert.equal(plan.metadata.created, '2026-04-10');
});

test('parsePlanXml reads tasks array with elements', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.equal(plan.tasks.length, 2);
  assert.equal(plan.tasks[0].id, 1);
  assert.equal(plan.tasks[0].risk, 'high');
  assert.equal(plan.tasks[0].status, 'pending');
  assert.equal(plan.tasks[0].name, 'Task one');
});

test('parsePlanXml reads task text fields', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.equal(plan.tasks[1].commit, 'abc1234');
  assert.equal(plan.tasks[1].closedAtVersion, 'plan-13-v2');
  assert.equal(plan.tasks[1].createdFrom, 'plan-13-v1');
});

test('parsePlanXml reads comments as array even with single element', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.ok(Array.isArray(plan.tasks[1].comments));
  assert.equal(plan.tasks[1].comments.length, 1);
  assert.equal(plan.tasks[1].comments[0].message, 'First comment');
  assert.equal(plan.tasks[1].comments[0].timestamp, '2026-04-10T12:00:00.000Z');
});

test('parsePlanXml reads deviations as array even with single element', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.ok(Array.isArray(plan.tasks[1].deviations));
  assert.equal(plan.tasks[1].deviations.length, 1);
  assert.equal(plan.tasks[1].deviations[0].type, 'minor');
  assert.equal(plan.tasks[1].deviations[0].message, 'Split task');
});

test('parsePlanXml returns empty arrays for empty comments/deviations', () => {
  const plan = parsePlanXml(EMPTY_COLLECTIONS_XML);
  assert.ok(Array.isArray(plan.tasks[0].comments));
  assert.equal(plan.tasks[0].comments.length, 0);
  assert.ok(Array.isArray(plan.tasks[0].deviations));
  assert.equal(plan.tasks[0].deviations.length, 0);
});

test('parsePlanXml reads project updates', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  assert.equal(plan.projectUpdates.length, 2);
  assert.equal(plan.projectUpdates[0].message, 'Plan initialised.');
  assert.equal(plan.projectUpdates[1].blocked, true);
  assert.equal(plan.projectUpdates[0].blocked, false);
});

// --- serializePlanXml round-trip ---

test('serializePlanXml round-trip preserves plan number', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  const xml2 = serializePlanXml(plan);
  const plan2 = parsePlanXml(xml2);
  assert.equal(plan2.planNum, 13);
});

test('serializePlanXml round-trip preserves tasks with elements', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  const xml2 = serializePlanXml(plan);
  const plan2 = parsePlanXml(xml2);
  assert.equal(plan2.tasks.length, 2);
  assert.equal(plan2.tasks[0].id, 1);
  assert.equal(plan2.tasks[0].status, 'pending');
  assert.equal(plan2.tasks[1].commit, 'abc1234');
});

test('serializePlanXml round-trip preserves comments and deviations', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  const xml2 = serializePlanXml(plan);
  const plan2 = parsePlanXml(xml2);
  assert.equal(plan2.tasks[1].comments.length, 1);
  assert.equal(plan2.tasks[1].comments[0].message, 'First comment');
  assert.equal(plan2.tasks[1].deviations.length, 1);
  assert.equal(plan2.tasks[1].deviations[0].type, 'minor');
});

test('serializePlanXml round-trip preserves project updates', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  const xml2 = serializePlanXml(plan);
  const plan2 = parsePlanXml(xml2);
  assert.equal(plan2.projectUpdates.length, 2);
  assert.equal(plan2.projectUpdates[1].blocked, true);
});

test('serializePlanXml preserves empty commit element (not self-closing)', () => {
  const plan = parsePlanXml(SAMPLE_XML);
  const xml = serializePlanXml(plan);
  assert.match(xml, /<commit><\/commit>/);
});
