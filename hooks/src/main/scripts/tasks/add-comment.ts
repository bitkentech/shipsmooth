import * as fs from 'node:fs';
import * as path from 'node:path';
import { parsePlanXml, serializePlanXml } from './xml-utils';

export function addComment(xml: string, taskId: number, message: string, timestamp: string): string {
  const plan = parsePlanXml(xml);
  const task = plan.tasks.find((t) => t.id === taskId);
  if (!task) {
    throw new Error(`Task ${taskId} not found in XML`);
  }
  task.comments.push({ timestamp, message });
  return serializePlanXml(plan);
}

// CLI entrypoint
if (require.main === module) {
  const args = process.argv.slice(2);
  const planIdx = args.indexOf('--plan');
  const taskIdx = args.indexOf('--task');
  const msgIdx = args.indexOf('--message');

  if (planIdx === -1 || taskIdx === -1 || msgIdx === -1) {
    console.error('Usage: node add-comment.js --plan <N> --task <id> --message <text>');
    process.exit(1);
  }

  const planNum = parseInt(args[planIdx + 1], 10);
  const taskId = parseInt(args[taskIdx + 1], 10);
  const message = args[msgIdx + 1];
  const timestamp = new Date().toISOString();

  const xmlPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
  const xml = fs.readFileSync(xmlPath, 'utf-8');
  const updated = addComment(xml, taskId, message, timestamp);
  fs.writeFileSync(xmlPath, updated, 'utf-8');
  console.log(`Comment added to task ${taskId}`);
}
