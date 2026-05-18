"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.parseTasksFromPlan = parseTasksFromPlan;
exports.generateXml = generateXml;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
// Parse `### Task N: Name [Risk]` lines from plan markdown.
// Risk is optional — defaults to empty string if not present.
function parseTasksFromPlan(markdown) {
    const tasks = [];
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
function isoDate() {
    return new Date().toISOString().slice(0, 10);
}
function generateXml(planNum, planVersion, tasks) {
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
function escapeXml(s) {
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
        if (tags)
            planVersion = tags.split('\n')[0].trim();
    }
    catch { /* ignore */ }
    const outPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
    const xml = generateXml(planNum, planVersion, tasks);
    fs.writeFileSync(outPath, xml, 'utf-8');
    console.log(`Written ${tasks.length} tasks to ${outPath}`);
}
