import { XMLParser, XMLBuilder } from 'fast-xml-parser';
import {
  PlanTasks, PlanTask, PlanMetadata, Comment, Deviation, ProjectUpdate,
  TaskStatus, RiskLevel,
} from './types';

const PARSER = new XMLParser({
  isArray: (name) => ['task', 'comment', 'deviation', 'update'].includes(name),
});

const BUILDER = new XMLBuilder({
  suppressEmptyNode: false,
  format: true,
  indentBy: '  ',
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
});

// ── Parse ────────────────────────────────────────────────────────────────────

export function parsePlanXml(xml: string): PlanTasks {
  const raw = PARSER.parse(xml);
  const root = raw['plan-tasks'];

  const planNum = Number(root['plan']);
  const planVersion = String(root['plan-version']);

  const rawMeta = root['metadata'];
  const metadata: PlanMetadata = {
    backlogIssue: String(rawMeta['backlog-issue']),
    status: String(rawMeta['status']) as PlanMetadata['status'],
    created: String(rawMeta['created']),
  };

  const rawTasks: unknown[] = root['tasks']['task'] ?? [];
  const tasks: PlanTask[] = rawTasks.map(parseTask);

  const rawUpdates: unknown[] = root['project-updates']?.['update'] ?? [];
  const projectUpdates: ProjectUpdate[] = rawUpdates.map(parseUpdate);

  return { planNum, planVersion, metadata, tasks, projectUpdates };
}

function parseTask(raw: unknown): PlanTask {
  const t = raw as Record<string, unknown>;
  const rawComments = (t['comments'] as Record<string, unknown> | undefined);
  const commentList: unknown[] = (rawComments?.['comment'] as unknown[] | undefined) ?? [];

  const rawDeviations = (t['deviations'] as Record<string, unknown> | undefined);
  const deviationList: unknown[] = (rawDeviations?.['deviation'] as unknown[] | undefined) ?? [];

  return {
    id: Number(t['id']),
    risk: String(t['risk']) as RiskLevel,
    status: String(t['status']) as TaskStatus,
    name: String(t['name']),
    commit: t['commit'] != null ? String(t['commit']) : '',
    createdFrom: t['created-from'] != null ? String(t['created-from']) : '',
    closedAtVersion: t['closed-at-version'] != null ? String(t['closed-at-version']) : '',
    comments: commentList.map(parseComment),
    deviations: deviationList.map(parseDeviation),
  };
}

function parseComment(raw: unknown): Comment {
  const c = raw as Record<string, unknown>;
  return {
    timestamp: String(c['timestamp']),
    message: String(c['message'] ?? ''),
  };
}

function parseDeviation(raw: unknown): Deviation {
  const d = raw as Record<string, unknown>;
  return {
    type: String(d['type']) as 'minor' | 'major',
    timestamp: String(d['timestamp']),
    message: String(d['message'] ?? ''),
  };
}

function parseUpdate(raw: unknown): ProjectUpdate {
  const u = raw as Record<string, unknown>;
  return {
    timestamp: String(u['timestamp']),
    blocked: u['blocked'] === true || u['blocked'] === 'true',
    message: String(u['message'] ?? ''),
  };
}

// ── Serialize ────────────────────────────────────────────────────────────────

export function serializePlanXml(plan: PlanTasks): string {
  const obj = {
    '?xml': { '@_version': '1.0', '@_encoding': 'UTF-8' },
    'plan-tasks': {
      'plan': plan.planNum,
      'plan-version': plan.planVersion,
      'metadata': {
        'backlog-issue': plan.metadata.backlogIssue,
        'status': plan.metadata.status,
        'created': plan.metadata.created,
      },
      'tasks': {
        'task': plan.tasks.map(serializeTask),
      },
      'project-updates': {
        'update': plan.projectUpdates.map(serializeUpdate),
      },
    },
  };
  // Note: builders still need @_ for the ?xml processing instruction attributes
  return BUILDER.build(obj) as string;
}

function serializeTask(task: PlanTask): Record<string, unknown> {
  return {
    'id': task.id,
    'risk': task.risk,
    'status': task.status,
    'name': task.name,
    'commit': task.commit,
    'created-from': task.createdFrom,
    'closed-at-version': task.closedAtVersion,
    'comments': task.comments.length > 0
      ? { 'comment': task.comments.map(serializeComment) }
      : '',
    'deviations': task.deviations.length > 0
      ? { 'deviation': task.deviations.map(serializeDeviation) }
      : '',
  };
}

function serializeComment(c: Comment): Record<string, unknown> {
  return {
    'timestamp': c.timestamp,
    'message': c.message,
  };
}

function serializeDeviation(d: Deviation): Record<string, unknown> {
  return {
    'type': d.type,
    'timestamp': d.timestamp,
    'message': d.message,
  };
}

function serializeUpdate(u: ProjectUpdate): Record<string, unknown> {
  return {
    'timestamp': u.timestamp,
    'message': u.message,
    'blocked': u.blocked,
  };
}
