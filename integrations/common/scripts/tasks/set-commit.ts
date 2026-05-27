import * as fs from 'node:fs';
import * as path from 'node:path';
import { parsePlanXml, serializePlanXml } from './xml-utils';

export function setCommit(xml: string, taskId: number, commit: string): string {
  const plan = parsePlanXml(xml);
  const task = plan.tasks.find((t) => t.id === taskId);
  if (!task) {
    throw new Error(`Task ${taskId} not found in XML`);
  }
  task.commit = commit;
  return serializePlanXml(plan);
}

// CLI entrypoint
if (require.main === module) {
  const args = process.argv.slice(2);
  const planIdx = args.indexOf('--plan');
  const taskIdx = args.indexOf('--task');
  const commitIdx = args.indexOf('--commit');

  if (planIdx === -1 || taskIdx === -1 || commitIdx === -1) {
    console.error('Usage: node set-commit.js --plan <N> --task <id> --commit <hash>');
    process.exit(1);
  }

  const planNum = parseInt(args[planIdx + 1], 10);
  const taskId = parseInt(args[taskIdx + 1], 10);
  const commit = args[commitIdx + 1];

  const xmlPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
  const xml = fs.readFileSync(xmlPath, 'utf-8');
  const updated = setCommit(xml, taskId, commit);
  fs.writeFileSync(xmlPath, updated, 'utf-8');
  console.log(`Commit set to "${commit}" on task ${taskId}`);
}
