import * as fs from 'node:fs';
import * as path from 'node:path';
import * as readline from 'node:readline';

export interface Task {
  id: number;
  name: string;
  risk: string;
}

// Parse `### Task N: Name [Risk]` lines from plan markdown.
// Risk is optional — defaults to empty string if not present.
export function parseTasksFromPlan(markdown: string): Task[] {
  const tasks: Task[] = [];
  const pattern = /^###\s+Task\s+(\d+):\s+(.+?)(?:\s+\[(High|Medium|Low)\])?\s*$/im;

  for (const line of markdown.split('\n')) {
    const m = line.match(pattern);
    if (m) {
      tasks.push({
        id: parseInt(m[1], 10),
        name: m[2].trim(),
        risk: m[3] ? m[3].toLowerCase() : '',
      });
    }
  }

  return tasks;
}

function isoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

export function generateXml(planNum: number, planVersion: string, tasks: Task[]): string {
  const taskXml = tasks.map(t => `
  <task>
    <id>${t.id}</id>
    <risk>${escapeXml(t.risk)}</risk>
    <status>pending</status>
    <name>${escapeXml(t.name)}</name>
    <commit></commit>
    <created-from>${escapeXml(planVersion)}</created-from>
    <closed-at-version></closed-at-version>
    <comments></comments>
    <deviations></deviations>
  </task>`).join('');

  return `<?xml version="1.0" encoding="UTF-8"?>
<plan-tasks>
  <plan>${planNum}</plan>
  <plan-version>${escapeXml(planVersion)}</plan-version>
  <metadata>
    <backlog-issue></backlog-issue>
    <status>active</status>
    <created>${isoDate()}</created>
  </metadata>

  <tasks>${taskXml}
  </tasks>

  <project-updates>
    <update>
      <timestamp>${new Date().toISOString()}</timestamp>
      <message>Plan initialised.</message>
      <blocked>false</blocked>
    </update>
  </project-updates>
</plan-tasks>
`;
}

function escapeXml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// CLI entrypoint
if (require.main === module) {
  const args = process.argv.slice(2);
  const planIdx = args.indexOf('--plan');
  const tasksFromIdx = args.indexOf('--tasks-from');

  if (planIdx === -1 || tasksFromIdx === -1) {
    console.error('Usage: node init.js --plan <N> --tasks-from <plan-file.md>');
    process.exit(1);
  }

  const planNum = parseInt(args[planIdx + 1], 10);
  const planFile = args[tasksFromIdx + 1];
  const markdown = fs.readFileSync(planFile, 'utf-8');
  const tasks = parseTasksFromPlan(markdown);

  // Derive plan version from latest git tag (best-effort; fall back to v1)
  let planVersion = `plan-${planNum}-v1`;
  try {
    const { execSync } = require('node:child_process');
    const tags = execSync(`git tag -l "plan-${planNum}-v*" --sort=-version:refname`, { encoding: 'utf-8' }).trim();
    if (tags) planVersion = tags.split('\n')[0].trim();
  } catch { /* ignore */ }

  const outPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
  const xml = generateXml(planNum, planVersion, tasks);
  fs.writeFileSync(outPath, xml, 'utf-8');
  console.log(`Written ${tasks.length} tasks to ${outPath}`);
}
