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
exports.formatPlanSummary = formatPlanSummary;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
function parseElement(xml, tag) {
    return xml.match(new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`))?.[1] ?? '';
}
function parseTasks(xml) {
    const rows = [];
    for (const block of xml.matchAll(/<task>([\s\S]*?)<\/task>/g)) {
        const b = block[1];
        rows.push({
            id: parseElement(b, 'id'),
            risk: parseElement(b, 'risk'),
            status: parseElement(b, 'status'),
            name: parseElement(b, 'name'),
            commit: parseElement(b, 'commit'),
        });
    }
    return rows;
}
function parseUpdates(xml) {
    const rows = [];
    for (const m of xml.matchAll(/<update>([\s\S]*?)<\/update>/g)) {
        const inner = m[1];
        rows.push({
            timestamp: parseElement(inner, 'timestamp'),
            blocked: parseElement(inner, 'blocked') === 'true',
            message: parseElement(inner, 'message'),
        });
    }
    return rows;
}
function formatPlanSummary(xml) {
    const planNum = parseElement(xml, 'plan');
    const planVersion = parseElement(xml, 'plan-version');
    const metaStatus = parseElement(xml, 'status');
    const backlogIssue = parseElement(xml, 'backlog-issue');
    const tasks = parseTasks(xml);
    const updates = parseUpdates(xml);
    const lines = [];
    lines.push(`Plan ${planNum} (${planVersion})  status: ${metaStatus}  backlog: ${backlogIssue || '—'}`);
    lines.push('');
    // Task table
    const colWidths = { id: 3, risk: 6, status: 12, name: 40, commit: 8 };
    const header = pad('ID', colWidths.id) + '  ' + pad('RISK', colWidths.risk) + '  ' + pad('STATUS', colWidths.status) + '  ' + pad('NAME', colWidths.name) + '  COMMIT';
    lines.push(header);
    lines.push('-'.repeat(header.length));
    for (const t of tasks) {
        lines.push(pad(t.id, colWidths.id) + '  ' +
            pad(t.risk, colWidths.risk) + '  ' +
            pad(t.status, colWidths.status) + '  ' +
            pad(t.name, colWidths.name) + '  ' +
            (t.commit || '—'));
    }
    lines.push('');
    lines.push('Project updates:');
    for (const u of updates) {
        const flag = u.blocked ? ' [BLOCKED]' : '';
        lines.push(`  ${u.timestamp}${flag}  ${u.message}`);
    }
    return lines.join('\n');
}
function pad(s, width) {
    const str = String(s);
    return str.length >= width ? str.slice(0, width) : str + ' '.repeat(width - str.length);
}
// CLI entrypoint
if (require.main === module) {
    const args = process.argv.slice(2);
    const planIdx = args.indexOf('--plan');
    if (planIdx === -1) {
        console.error('Usage: node show.js --plan <N>');
        process.exit(1);
    }
    const planNum = parseInt(args[planIdx + 1], 10);
    const xmlPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
    const xml = fs.readFileSync(xmlPath, 'utf-8');
    console.log(formatPlanSummary(xml));
}
