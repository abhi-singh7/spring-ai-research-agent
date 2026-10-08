export type ResearchStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface ResearchSession {
  id: string;
  topic: string;
  status: ResearchStatus;
  createdAt?: string;
  updatedAt?: string;
  completedAt?: string;
  finalReport?: string;
  streamUrl?: string;
  steps?: HistoryDetailStep[];
  followUps?: FollowUpExchange[];
}

export interface HistoryDetailStep {
  orderIndex: number;
  type: StepType;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
  content?: string;
}

export type StepType = 'BREAKDOWN' | 'SEARCH' | 'READ' | 'SYNTHESIS' | 'SUBTOPIC' | 'FINAL_REPORT';

export interface ResearchStartRequest {
  topic: string;
  streamingEnabled?: boolean;
  maxIterations?: number;
  subTopicCount?: number;
}

export interface ResearchHistoryItem {
  id: string;
  topic: string;
  status: ResearchStatus;
  createdAt: string;
  updatedAt?: string;
}

export interface PaginatedResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  size: number;
}

// SSE Event Types
export type SseEventType = 'PROGRESS' | 'CONTENT' | 'REPORT_CHUNK' | 'REPORT_DONE' | 'STEP_COMPLETE' | 'ERROR' | 'REPORT_START';

export interface SseEvent {
  type: SseEventType;
  sessionId?: string;
}

export interface ProgressSseEvent extends SseEvent {
  type: 'PROGRESS';
  payload: string;
}

export interface ContentSseEvent extends SseEvent {
  type: 'CONTENT';
  payload: string;
}

export interface ReportChunkSseEvent extends SseEvent {
  type: 'REPORT_CHUNK';
  payload: string;
}

export interface ReportDoneSseEvent extends SseEvent {
  type: 'REPORT_DONE';
  payload: string;
}

export interface StepCompleteSseEvent extends SseEvent {
  type: 'STEP_COMPLETE';
  payload: Record<string, unknown>;
}

export interface ErrorSseEvent extends SseEvent {
  type: 'ERROR';
  payload: string;
}

export interface ReportStartSseEvent extends SseEvent {
  type: 'REPORT_START';
  sessionId?: string;
}

// ResearchStep (from polling / history)
export interface ResearchStep {
  stepNumber: number;
  name: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
  description?: string;
  duration?: number;
}

// Follow-up Request / Stored exchange
export interface FollowUpRequest {
  question: string;
}

/** A stored follow-up Q&A exchange (embedded on the session document, chronological). */
export interface FollowUpExchange {
  id: string;
  question: string;
  answer: string;
  createdAt: string;
}
