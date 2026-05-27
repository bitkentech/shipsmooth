#!/usr/bin/env npx ts-node
// Migration script: convert attribute-based plan XML to element-based format.
// Parses with old-format config, serializes with new element-based logic.
// Usage: npx ts-node migrate-plans.ts [file1.xml file2.xml ...]
// If no files given, migrates all .agents/plans/plan-*-tasks.xml

import * as fs from 'node:fs';
import * as path from 'node:path';
import { XMLParser, XMLBuilder } from 'fast-xml-parser';

// Old-format parser: reads attributes via @_ prefix
const OLD_PARSER = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
  allowBooleanAttributes: true,
  isArray: (name: string) => ['task', 'comment', 'deviation', 'update'].includes(name),
});

// New-format builder: element-only (attributes only for ?xml PI)
const NEW_BUILDER = new XMLBuilder({
  suppressEmptyNode: false,
  format: true,
  indentBy: '  ',
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
});

function migrateXml(xml: string): string {
  const raw = OLD_PARSER.parse(xml) as Record<string, unknown>;
  const root = raw['plan-tasks'] as Record<string, unknown>;

  // plan-tasks attributes → child elements
  const planNum = root['@_plan'] != null ? root['@_plan'] : root['plan'];
  const planVersion = root['@_plan-version'] != null ? root['@_plan-version'] : root['plan-version'];

  const rawMeta = root['metadata'] as Record<string, unknown>;
  const rawTasks = ((root['tasks'] as Record<string, unknown>)?.['task'] as unknown[]) ?? [];
  const rawUpdates = ((root['project-updates'] as Record<string, unknown>)?.['update'] as unknown[]) ?? [];

  const obj = {
    '?xml': { '@_version': '1.0', '@_encoding': 'UTF-8' },
    'plan-tasks': {
      'plan': planNum,
      'plan-version': planVersion,
      'metadata': {
        'backlog-issue': rawMeta['backlog-issue'] ?? '',
        'status': rawMeta['status'] ?? '',
        'created': rawMeta['created'] ?? '',
      },
      'tasks': { 'task': rawTasks.map(migrateTask) },
      'project-updates': { 'update': rawUpdates.map(migrateUpdate) },
    },
  };

  return NEW_BUILDER.build(obj) as string;
}

function migrateTask(raw: unknown): Record<string, unknown> {
  const t = raw as Record<string, unknown>;
  const id = t['@_id'] != null ? t['@_id'] : t['id'];
  const risk = t['@_risk'] != null ? t['@_risk'] : t['risk'];
  const status = t['@_status'] != null ? t['@_status'] : t['status'];

  const rawComments = t['comments'] as Record<string, unknown> | undefined;
  const commentList = ((rawComments?.['comment'] as unknown[]) ?? []).map(migrateComment);

  const rawDeviations = t['deviations'] as Record<string, unknown> | undefined;
  const deviationList = ((rawDeviations?.['deviation'] as unknown[]) ?? []).map(migrateDeviation);

  return {
    'id': id,
    'risk': risk ?? '',
    'status': status ?? '',
    'name': t['name'] ?? '',
    'commit': t['commit'] ?? '',
    'created-from': t['created-from'] ?? '',
    'closed-at-version': t['closed-at-version'] ?? '',
    'comments': commentList.length > 0 ? { 'comment': commentList } : '',
    'deviations': deviationList.length > 0 ? { 'deviation': deviationList } : '',
  };
}

function migrateComment(raw: unknown): Record<string, unknown> {
  const c = raw as Record<string, unknown>;
  const timestamp = c['@_timestamp'] != null ? c['@_timestamp'] : c['timestamp'];
  const message = c['#text'] != null ? String(c['#text']) : (c['message'] ?? '');
  return { 'timestamp': timestamp ?? '', 'message': message };
}

function migrateDeviation(raw: unknown): Record<string, unknown> {
  const d = raw as Record<string, unknown>;
  const type = d['@_type'] != null ? d['@_type'] : d['type'];
  const timestamp = d['@_timestamp'] != null ? d['@_timestamp'] : d['timestamp'];
  const message = d['#text'] != null ? String(d['#text']) : (d['message'] ?? '');
  return { 'type': type ?? '', 'timestamp': timestamp ?? '', 'message': message };
}

function migrateUpdate(raw: unknown): Record<string, unknown> {
  const u = raw as Record<string, unknown>;
  const timestamp = u['@_timestamp'] != null ? u['@_timestamp'] : u['timestamp'];
  const blockedRaw = u['@_blocked'] != null ? u['@_blocked'] : u['blocked'];
  const blocked = blockedRaw === true || blockedRaw === 'true';
  const message = u['#text'] != null ? String(u['#text']) : (u['message'] ?? '');
  return { 'timestamp': timestamp ?? '', 'message': message, 'blocked': blocked };
}

// Determine files to migrate
const args = process.argv.slice(2);
const files: string[] = args.length > 0
  ? args
  : fs.readdirSync('.agents/plans')
      .filter(f => /^plan-\d+-tasks\.xml$/.test(f))
      .map(f => path.join('.agents', 'plans', f));

let migrated = 0;
let skipped = 0;

for (const file of files) {
  const xml = fs.readFileSync(file, 'utf-8');

  // Skip files already in element-based format (no attributes on plan-tasks)
  if (!/<plan-tasks\s/.test(xml)) {
    console.log(`SKIP (already migrated): ${file}`);
    skipped++;
    continue;
  }

  try {
    const newXml = migrateXml(xml);
    fs.writeFileSync(file, newXml, 'utf-8');
    console.log(`MIGRATED: ${file}`);
    migrated++;
  } catch (err) {
    console.error(`ERROR migrating ${file}:`, (err as Error).message);
  }
}

console.log(`\nDone. Migrated: ${migrated}, Skipped: ${skipped}`);
