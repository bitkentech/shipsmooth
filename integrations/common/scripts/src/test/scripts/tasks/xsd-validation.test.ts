import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';

// __dirname at runtime is dist-test/test/scripts/tasks (4 levels deep in plugin-node)
// plugin-node root = 4 levels up; repo root = 5 levels up
const PLUGIN_NODE_ROOT = path.resolve(__dirname, '../../../../');
const REPO_ROOT = path.resolve(PLUGIN_NODE_ROOT, '..');
const XSD_PATH = path.join(PLUGIN_NODE_ROOT, 'src/main/scripts/tasks/plan-tasks.xsd');
const PLANS_DIR = path.join(REPO_ROOT, '.agents/plans');

function validateXmlFile(xmlPath: string): { valid: boolean; error: string } {
  try {
    execFileSync('xmllint', ['--schema', XSD_PATH, '--noout', xmlPath], {
      stdio: ['ignore', 'ignore', 'pipe'],
    });
    return { valid: true, error: '' };
  } catch (e: unknown) {
    const err = e as { stderr?: Buffer };
    return { valid: false, error: err.stderr?.toString() ?? String(e) };
  }
}

function validateXmlString(xml: string): { valid: boolean; error: string } {
  const result = spawnSync(
    'xmllint',
    ['--schema', XSD_PATH, '--noout', '-'],
    { input: xml, encoding: 'utf8' }
  );
  if (result.status === 0) return { valid: true, error: '' };
  return { valid: false, error: result.stderr ?? '' };
}

test('XSD file exists', () => {
  assert.ok(existsSync(XSD_PATH), `XSD not found at ${XSD_PATH}`);
});

test('all existing plan task XML files are valid against the XSD', () => {
  const xmlFiles = readdirSync(PLANS_DIR)
    .filter(f => /^plan-\d+-tasks\.xml$/.test(f))
    .map(f => path.join(PLANS_DIR, f));

  assert.ok(xmlFiles.length > 0, 'No plan task XML files found');

  for (const xmlFile of xmlFiles) {
    const result = validateXmlFile(xmlFile);
    assert.ok(result.valid, `${path.basename(xmlFile)} failed XSD validation:\n${result.error}`);
  }
});

test('invalid metadata status value fails validation', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>1</plan>
  <plan-version>plan-1-v1</plan-version>
  <metadata>
    <backlog-issue>PB-1</backlog-issue>
    <status>invalid-status</status>
    <created>2026-01-01</created>
  </metadata>
  <tasks></tasks>
  <project-updates></project-updates>
</plan-tasks>`;
  const result = validateXmlString(xml);
  assert.ok(!result.valid, 'Expected validation to fail for invalid metadata status');
});

test('invalid task status value fails validation', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>1</plan>
  <plan-version>plan-1-v1</plan-version>
  <metadata>
    <backlog-issue></backlog-issue>
    <status>active</status>
    <created>2026-01-01</created>
  </metadata>
  <tasks>
    <task>
      <id>1</id>
      <risk>high</risk>
      <status>not-a-status</status>
      <name>Test</name>
      <commit></commit>
      <created-from>plan-1-v1</created-from>
      <closed-at-version></closed-at-version>
      <comments></comments>
      <deviations></deviations>
    </task>
  </tasks>
  <project-updates></project-updates>
</plan-tasks>`;
  const result = validateXmlString(xml);
  assert.ok(!result.valid, 'Expected validation to fail for invalid task status');
});

test('invalid risk value fails validation', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>1</plan>
  <plan-version>plan-1-v1</plan-version>
  <metadata>
    <backlog-issue></backlog-issue>
    <status>active</status>
    <created>2026-01-01</created>
  </metadata>
  <tasks>
    <task>
      <id>1</id>
      <risk>extreme</risk>
      <status>pending</status>
      <name>Test</name>
      <commit></commit>
      <created-from>plan-1-v1</created-from>
      <closed-at-version></closed-at-version>
      <comments></comments>
      <deviations></deviations>
    </task>
  </tasks>
  <project-updates></project-updates>
</plan-tasks>`;
  const result = validateXmlString(xml);
  assert.ok(!result.valid, 'Expected validation to fail for invalid risk value');
});

test('empty risk value passes validation', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>1</plan>
  <plan-version>plan-1-v1</plan-version>
  <metadata>
    <backlog-issue></backlog-issue>
    <status>active</status>
    <created>2026-01-01</created>
  </metadata>
  <tasks>
    <task>
      <id>1</id>
      <risk></risk>
      <status>pending</status>
      <name>Test</name>
      <commit></commit>
      <created-from>plan-1-v1</created-from>
      <closed-at-version></closed-at-version>
      <comments></comments>
      <deviations></deviations>
    </task>
  </tasks>
  <project-updates></project-updates>
</plan-tasks>`;
  const result = validateXmlString(xml);
  assert.ok(result.valid, `Expected empty risk to pass validation, got:\n${result.error}`);
});

test('missing required element fails validation', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan-version>plan-1-v1</plan-version>
  <metadata>
    <backlog-issue></backlog-issue>
    <status>active</status>
    <created>2026-01-01</created>
  </metadata>
  <tasks></tasks>
  <project-updates></project-updates>
</plan-tasks>`;
  const result = validateXmlString(xml);
  assert.ok(!result.valid, 'Expected validation to fail when plan element is missing');
});

test('unknown extension elements in metadata and task pass validation (xs:any)', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>1</plan>
  <plan-version>plan-1-v1</plan-version>
  <metadata>
    <backlog-issue>PB-1</backlog-issue>
    <status>active</status>
    <created>2026-01-01</created>
    <target-date>2026-06-01</target-date>
    <branch>t/pb-1-some-feature</branch>
  </metadata>
  <tasks>
    <task>
      <id>1</id>
      <risk>high</risk>
      <status>pending</status>
      <name>Test</name>
      <commit></commit>
      <created-from>plan-1-v1</created-from>
      <closed-at-version></closed-at-version>
      <comments></comments>
      <deviations></deviations>
      <estimate>3</estimate>
      <labels>ux,backend</labels>
    </task>
  </tasks>
  <project-updates></project-updates>
</plan-tasks>`;
  const result = validateXmlString(xml);
  assert.ok(result.valid, `Expected unknown extension elements to pass validation (xs:any), got:\n${result.error}`);
});
