export type TaskStatus =
  | 'pending'
  | 'in-progress'
  | 'de-risked'
  | 'agent-coded'
  | 'closed'
  | 'needs-triage'
  | 'abandoned';

export type RiskLevel = 'high' | 'medium' | 'low' | '';

export interface PlanTask {
  id: number;
  risk: RiskLevel;
  status: TaskStatus;
  name: string;
  commit: string;
  createdFrom: string;
  closedAtVersion: string;
  comments: Comment[];
  deviations: Deviation[];
}

export interface Comment {
  timestamp: string;
  message: string;
}

export interface Deviation {
  type: 'minor' | 'major';
  timestamp: string;
  message: string;
}

export interface ProjectUpdate {
  timestamp: string;
  blocked: boolean;
  message: string;
}

export interface PlanMetadata {
  backlogIssue: string;
  status: 'active' | 'complete' | 'abandoned' | 'in-review';
  created: string;
}

export interface PlanTasks {
  planNum: number;
  planVersion: string;
  metadata: PlanMetadata;
  tasks: PlanTask[];
  projectUpdates: ProjectUpdate[];
}
