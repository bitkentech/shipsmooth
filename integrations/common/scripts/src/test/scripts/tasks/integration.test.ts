import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePlanXml, serializePlanXml } from '../../../main/scripts/tasks/xml-utils';
import { PlanTasks } from '../../../main/scripts/tasks/types';

test('Integration: element-based XML format', async () => {
  // This XML structure represents the future element-based format from Plan 24
  const xmlInput = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>24</plan>
  <plan-version>plan-24-v1</plan-version>
  <metadata>
    <backlog-issue>PB-275</backlog-issue>
    <status>active</status>
    <created>2026-04-16</created>
  </metadata>
  <tasks>
    <task>
      <id>1</id>
      <risk>high</risk>
      <status>pending</status>
      <name>Task one</name>
      <commit></commit>
      <created-from>v1</created-from>
      <closed-at-version></closed-at-version>
      <comments>
        <comment>
          <timestamp>2026-04-16T10:00:00.000Z</timestamp>
          <message>A comment.</message>
        </comment>
      </comments>
      <deviations></deviations>
    </task>
  </tasks>
  <project-updates>
    <update>
      <timestamp>2026-04-16T10:00:00.000Z</timestamp>
      <message>Plan initialised.</message>
      <blocked>false</blocked>
    </update>
  </project-updates>
</plan-tasks>`;

  // This should fail to parse correctly with the current xml-utils.ts
  const parsedData = parsePlanXml(xmlInput);

  assert.strictEqual(parsedData.planNum, 24, 'Should parse plan number');
  assert.strictEqual(parsedData.planVersion, 'plan-24-v1', 'Should parse plan version');
  assert.strictEqual(parsedData.tasks.length, 1, 'Should have 1 task');
  assert.strictEqual(parsedData.tasks[0].id, 1, 'Task ID should be 1');
  assert.strictEqual(parsedData.tasks[0].comments.length, 1, 'Should have 1 comment');
  assert.strictEqual(parsedData.tasks[0].comments[0].message, 'A comment.', 'Comment message mismatch');
  assert.strictEqual(parsedData.projectUpdates[0].blocked, false, 'Update blocked status mismatch');

  // Serialize back
  const serializedXml = serializePlanXml(parsedData);

  assert.match(serializedXml, /<plan>24<\/plan>/);
  assert.match(serializedXml, /<plan-version>plan-24-v1<\/plan-version>/);
  assert.match(serializedXml, /<id>1<\/id>/);
  assert.match(serializedXml, /<comment>[\s\S]*?<timestamp>[\s\S]*?<message>A comment.<\/message>/);
});
