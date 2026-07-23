import { Injectable, signal, computed, inject, DestroyRef } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { Subject, Observable, tap } from 'rxjs';
import * as ResearchModels from '../models/research.model';

@Injectable({ providedIn: 'root' })
export class ResearchService {

  private readonly baseUrl = environment.apiUrl + '/api/research';
  private http = inject(HttpClient);
  private destroyRef = inject(DestroyRef);

  // Reactive State via Signals
  /** Currently active research session */
  readonly researchSession = signal<ResearchModels.ResearchSession | null>(null);

  /** List of research steps as received via SSE events */
  private _researchStepsSignal = signal<ResearchModels.ResearchStep[]>([]);
  readonly researchSteps = this._researchStepsSignal.asReadonly();

  /** Whether the SSE connection is live and receiving events */
  readonly isStreaming = signal(false);

  /** Accumulated final report content (built from REPORT_CHUNK events) */
  private _reportContentSignal = signal<string>('');
  readonly reportContent = this._reportContentSignal.asReadonly();

  /** Error notifications - components subscribe to show inline toast notifications */
  private readonly errorSubject = new Subject<string>();
  readonly error$ = this.errorSubject.asObservable();

  /** Whether to display accumulated streaming content before final report is ready */
  readonly shouldDisplayStreamingContent = computed(() => {
    const session = this.researchSession();
    return session !== null && !session.finalReport && session.status === 'PROCESSING';
  });

  // Derived State via Computed Signals
  readonly progressPercent = computed(() => {
    const steps = this.researchSteps();
    if (!steps.length) return 0;
    const completedCount = steps.filter(s => s.status === 'COMPLETED').length;
    const inProgressCount = steps.filter(s => s.status === 'IN_PROGRESS').length;
    
    // Each COMPLETED step = 1 point, each IN_PROGRESS step = 0.5 points (partial credit)
    const progressPoints = completedCount + (inProgressCount * 0.5);
    return Math.round((progressPoints / steps.length) * 100);
  });

  readonly activeStepIndex = computed(() => {
    const steps = this.researchSteps();
    const idx = steps.findIndex(s => s.status === 'IN_PROGRESS');
    return idx >= 0 ? idx : -1;
  });

  // SSE Connection (Primary method)
  private sseSource: EventSource | null = null;
  private stallTimer: ReturnType<typeof setTimeout> | null = null;

  /** Connect to the SSE stream for a given session */
  connectSse(sessionId: string, streamUrl?: string): void {
    this.disconnectSse();

    const url = streamUrl || `${this.baseUrl}/stream/${sessionId}`;
    this.sseSource = new EventSource(url);
    this.isStreaming.set(true);

    // PROGRESS events - update the active step and progress
    this.sseSource.addEventListener('PROGRESS', (event: unknown) => {
      const data: ResearchModels.ProgressSseEvent = JSON.parse((event as MessageEvent).data);
      this.handleProgress(data);
    });

    // CONTENT events - streaming text content as it arrives
    this.sseSource.addEventListener('CONTENT', (event: unknown) => {
      const data: ResearchModels.ContentSseEvent = JSON.parse((event as MessageEvent).data);
      this.appendContent(data.payload);
    });

    // REPORT_CHUNK events - accumulate the final report and reset stall timer on every chunk
    let lastChunkTime = 0;
    this.sseSource.addEventListener('REPORT_CHUNK', (event: unknown) => {
      const data: ResearchModels.ReportChunkSseEvent = JSON.parse((event as MessageEvent).data);
      this._reportContentSignal.update(content => content + data.payload);

      // Reset the stall timer on every chunk arrival — LLMs stream slowly on local models
      lastChunkTime = Date.now();
      if (this.stallTimer) {
        clearTimeout(this.stallTimer);
      }
      this.startStallTimer(sessionId);
    });

    // REPORT_DONE events - full report is ready
    this.sseSource.addEventListener('REPORT_DONE', (event: unknown) => {
      const data: ResearchModels.ReportDoneSseEvent = JSON.parse((event as MessageEvent).data);
      this._reportContentSignal.set(data.payload);
      this.clearStallTimer();

      // Only update steps if session status hasn't been changed by polling yet
      const currentSessionStatus = this.researchSession()?.status;
      if (!currentSessionStatus || currentSessionStatus === 'PROCESSING') {
        this._researchStepsSignal.update(prev => prev.map(s => ({
          ...s,
          status: (s.status === 'IN_PROGRESS' ? 'COMPLETED' as ResearchModels.ResearchStep['status'] : s.status)
        })));
      }

      // Update session status to COMPLETED regardless of previous state
      this.researchSession.update(s => s ? ({ ...s, status: 'COMPLETED' }) : null);
      // Stop streaming — report is fully received, no more events expected
      this.isStreaming.set(false);
    });

    // STEP_COMPLETE events - mark a step as complete
    this.sseSource.addEventListener('STEP_COMPLETE', (event: unknown) => {
      const data: ResearchModels.StepCompleteSseEvent = JSON.parse((event as MessageEvent).data);
      this.handleStepComplete(data);
    });

    // ERROR events - handle errors from the backend
    this.sseSource.addEventListener('ERROR', (event: unknown) => {
      const data: ResearchModels.ErrorSseEvent = JSON.parse((event as MessageEvent).data);
      this.errorSubject.next(data.payload);
      this.researchSession.update(s => s ? ({ ...s, status: 'FAILED' }) : null);
    });

    // REPORT_START events - report generation has begun (start stall timer)
    this.sseSource.addEventListener('REPORT_START', (event: unknown) => {
      const data: ResearchModels.ReportStartSseEvent = JSON.parse((event as MessageEvent).data);
      let steps = this.researchSteps();

      if (!steps.some(s => s.name === 'Generating Report')) {
        // First REPORT_START — add "Generating Report" step
        // If there's a dynamic placeholder step but no real sub-topic steps, remove it first
        if (steps.length === 1 && steps[0].name === 'Researching Sub-topics') {
          this._researchStepsSignal.set([]);
          steps = [];
        }

        // Mark any remaining IN_PROGRESS sub-topic steps as COMPLETED before adding report step
        const inProgressSubTopics = steps.filter(s => s.status === 'IN_PROGRESS');
        if (inProgressSubTopics.length > 0) {
          this._researchStepsSignal.update(prev => prev.map((s, i) => ({
            ...s,
            status: (s.status === 'IN_PROGRESS' ? 'COMPLETED' : s.status) as ResearchModels.ResearchStep['status']
          })));
        }

        this._researchStepsSignal.update(prev => [
          ...prev,
          { stepNumber: prev.length + 1, name: 'Generating Report', status: 'IN_PROGRESS' as ResearchModels.ResearchStep['status'] }
        ]);
      } else if (steps.some(s => s.name === 'Generating Report')) {
        // "Generating Report" already exists — ensure IN_PROGRESS
        const idx = steps.findIndex(s => s.name === 'Generating Report');
        if (idx >= 0) {
          this._researchStepsSignal.update(prev => prev.map((s, i) =>
            i === idx ? { ...s, status: 'IN_PROGRESS' as ResearchModels.ResearchStep['status'] } : s
          ));
        }
      }

      // Mark the previous IN_PROGRESS step as COMPLETED (if any exists)
      const updatedSteps = this.researchSteps();
      const prevInProgressIdx = updatedSteps.findIndex(s => s.status === 'IN_PROGRESS' && s.name !== 'Generating Report');
      if (prevInProgressIdx >= 0) {
        this._researchStepsSignal.update(prev => prev.map((s, i) => ({
          ...s,
          status: (i === prevInProgressIdx ? 'COMPLETED' : s.status) as ResearchModels.ResearchStep['status']
        })));
      }

      // Start stall timer — if no REPORT_CHUNK arrives within 30s, force completion via polling
      this.startStallTimer(sessionId);
    });

    // Open event - connection established
    this.sseSource.addEventListener('open', () => {
      console.log('[ResearchService] SSE connected for session:', sessionId);
    });

    // Error / reconnect handling with exponential backoff
    let reconnectAttempts = 0;
    const maxReconnectAttempts = 3;

    const onError = () => {
      if (this.sseSource && this.sseSource.readyState === EventSource.CLOSED) {
        console.warn('[ResearchService] SSE connection closed for session:', sessionId);
        this.clearStallTimer();
        this.isStreaming.set(false);
        const currentSession = this.researchSession();

        // If we're in a terminal state, no need to reconnect or poll
        if (currentSession && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(currentSession.status)) {
          return;
        }

        // Try reconnection with exponential backoff before falling back to polling
        if (reconnectAttempts < maxReconnectAttempts) {
          const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 8000); // 1s, 2s, 4s, capped at 8s
          console.info(`[ResearchService] Attempting SSE reconnection in ${delay}ms (attempt ${reconnectAttempts + 1}/${maxReconnectAttempts})...`);

          this.errorSubject.next('Connection lost — reconnecting...');

          setTimeout(() => {
            if (this.researchSession()?.status === 'PROCESSING') {
              try {
                this.connectSse(sessionId);
                reconnectAttempts = 0; // Reset on successful reconnection
                console.info('[ResearchService] SSE reconnected successfully');
              } catch {
                reconnectAttempts++;
              }
            }
          }, delay);
        } else {
          // Exhausted reconnection attempts — fall back to polling
          console.warn('[ResearchService] Max SSE reconnection attempts reached, falling back to polling.');
          this.errorSubject.next('Connection unstable — switching to background polling.');
          this.startPolling(sessionId);
        }
      } else {
        console.info('[ResearchService] SSE reconnecting...');
      }
    };
    this.sseSource.addEventListener('error', onError);

    // Cleanup on component destroy
    const cleanup = () => this.disconnectSse();
    this.destroyRef.onDestroy(cleanup);
  }

  /** Disconnect the current SSE connection */
  disconnectSse(): void {
    this.clearStallTimer();
    if (this.sseSource) {
      this.sseSource.close();
      this.sseSource = null;
      this.isStreaming.set(false);
    }
  }

  // Stall detection: if no REPORT_CHUNK arrives within 90s of report start, force completion via polling
  private startStallTimer(sessionId: string): void {
    this.clearStallTimer();
    this.stallTimer = setTimeout(() => {
      console.warn('[ResearchService] Report generation stalled — forcing completion via polling.');
      this.errorSubject.next('Report generation is taking longer than expected. Checking status in background...');
      this.startPolling(sessionId);
    }, 90000);
  }

  private clearStallTimer(): void {
    if (this.stallTimer) {
      clearTimeout(this.stallTimer);
      this.stallTimer = null;
    }
  }

  // Polling Fallback
  private pollingTimer: ReturnType<typeof setInterval> | null = null;

  startPolling(sessionId: string): void {
    if (this.pollingTimer) return;
    this.pollStatus(sessionId);
    this.pollingTimer = setInterval(() => this.pollStatus(sessionId), 5000);
  }

  stopPolling(): void {
    this.clearStallTimer();
    if (this.pollingTimer) {
      clearInterval(this.pollingTimer);
      this.pollingTimer = null;
    }
  }

  private pollStatus(sessionId: string): void {
    this.getStatus(sessionId).subscribe({
      next: (session) => {
        const wasTerminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(this.researchSession()?.status ?? '');

        // Sync steps from backend when session is in terminal state and wasn't already
        if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status) && !wasTerminal) {
          const newSteps = (session as any).steps?.map((step: any, i: number) => ({
            stepNumber: step.orderIndex + 1,
            name: this.getStepName(step.type),
            status: step.status as ResearchModels.ResearchStep['status'],
            description: step.content || ''
          })) ?? [];
          if (newSteps.length > 0) {
            this._researchStepsSignal.set(newSteps);
          } else {
            // No steps from backend — clear dangling frontend-only steps (e.g., stuck "Generating Report")
            this._researchStepsSignal.set([]);
          }

          // If session is COMPLETED and has a final report, sync it into the signal too
          if ((session as any).finalReport) {
            this._reportContentSignal.set((session as any).finalReport);
          }
        }

        this.researchSession.set(session);
        if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status)) {
          this.stopPolling();
          this.isStreaming.set(false);
        }
      },
      error: () => {}  // Ignore transient polling errors — next poll will retry
    });
  }

  /** Convert backend step type to human-readable name */
  getStepName(type?: string): string {
    const nameMap: Record<string, string> = {
      BREAKDOWN: 'Breakdown',
      SUBTOPIC: 'Sub-topic',
      SEARCH: 'Search',
      READ: 'Read URL',
      SYNTHESIS: 'Synthesis',
      FINAL_REPORT: 'Generating Report'
    };
    const baseName = type ? nameMap[type] || type : '';
    // Append step number for sub-topics to avoid duplicate names
    if (type === 'SUBTOPIC') {
      return `${baseName} ${this.researchSteps().filter(s => s.name.startsWith('Sub-topic')).length + 1}`;
    }
    return baseName || type || '';
  }

  // REST API Methods
  startResearch(request: ResearchModels.ResearchStartRequest): Observable<ResearchModels.ResearchSession> {
    return new Observable(observer => {
      console.log('[ResearchService] POST /api/research payload:', JSON.stringify(request));
      const headers = new HttpHeaders({ 'Content-Type': 'application/json' });
      this.http.post<ResearchModels.ResearchSession>(this.baseUrl, request, { headers }).subscribe({
        next: session => {
          // Try SSE first; fall back to polling if connection fails
          try {
            this.connectSse(session.id);
          } catch {
            this.startPolling(session.id);
          }
          observer.next(session);
        },
        error: err => {
          console.error('[ResearchService] POST /api/research failed:', JSON.stringify(err));
          observer.error(err)
        }
      });
    });
  }

  getStatus(sessionId: string): Observable<ResearchModels.ResearchSession> {
    return this.http.get<ResearchModels.ResearchSession>(`${this.baseUrl}/${sessionId}`);
  }

  cancelResearch(sessionId: string): Observable<void> {
    return new Observable(observer => {
      this.http.delete<void>(`${this.baseUrl}/${sessionId}`).subscribe({
        next: () => {
          this.disconnectSse();
          this._researchStepsSignal.set([]);
          this._reportContentSignal.set('');
          this.isStreaming.set(false);
          this.researchSession.set(null);
          observer.next(undefined as void);
        },
        error: err => observer.error(err)
      });
    });
  }

  /** Convert backend HistoryDetailStep[] to frontend ResearchStep[] and sync into signal */
  setStepsFromHistory(steps: Array<{ orderIndex: number; type?: string; status: string; content?: string }>): void {
    if (!steps || steps.length === 0) return;
    const newSteps = steps.map((step, i) => ({
      stepNumber: step.orderIndex + 1,
      name: this.getStepName(step.type),
      status: step.status as ResearchModels.ResearchStep['status'],
      description: step.content || ''
    }));
    this._researchStepsSignal.set(newSteps);
  }

  getHistoricalSession(sessionId: string): Observable<ResearchModels.ResearchSession> {
    return new Observable(observer => {
      this.http.get<any>(`${this.baseUrl}/history/${sessionId}`).subscribe({
        next: (session) => {
          observer.next(session as ResearchModels.ResearchSession);
        },
        error: err => observer.error(err)
      });
    });
  }

  submitFollowUp(sessionId: string, question: string): Observable<string> {
    const request: ResearchModels.FollowUpRequest = { question };
    return this.http.post<string>(`${this.baseUrl}/${sessionId}/followup`, request);
  }

  // SSE Event Handlers (private)
  private handleProgress(event: ResearchModels.ProgressSseEvent): void {
    const steps = this.researchSteps();

    if (!steps.length) {
      // First progress event during sub-topic research — create dynamic step
      this._researchStepsSignal.set([
        {
          stepNumber: 1,
          name: 'Researching Sub-topics',
          status: 'IN_PROGRESS' as ResearchModels.ResearchStep['status'],
          description: typeof event.payload === 'string' ? event.payload : JSON.stringify(event.payload)
        }
      ]);
      return;
    }

    // If session is already COMPLETED or FAILED (REPORT_DONE/ERROR arrived first), just update the last IN_PROGRESS step's description
    const currentSession = this.researchSession();
    if (currentSession && ['COMPLETED', 'FAILED'].includes(currentSession.status)) {
      this._researchStepsSignal.update(prev => prev.map((s, i) => ({
        ...s,
        status: (i === Math.max(0, steps.length - 1) ? s.status : 'COMPLETED') as ResearchModels.ResearchStep['status'],
        description: typeof event.payload === 'string' ? event.payload : JSON.stringify(event.payload)
      })));
      return;
    }

    const payloadStr = typeof event.payload === 'string' ? event.payload : JSON.stringify(event.payload);
    
    // Check if this is a sub-topic completion notification ("Completed research on: X")
    const isSubTopicCompletion = payloadStr.startsWith('Completed research on:') || payloadStr.startsWith('Research complete!');

    if (isSubTopicCompletion) {
      // Mark the current IN_PROGRESS step as COMPLETED and create new IN_PROGRESS step for the next sub-topic
      const inProgressIdx = steps.findIndex(s => s.status === 'IN_PROGRESS');
      this._researchStepsSignal.update(prev => prev.map((s, i) => ({
        ...s,
        status: (i === inProgressIdx ? 'COMPLETED' : s.status) as ResearchModels.ResearchStep['status']
      })));

      // Only create a new IN_PROGRESS step if there isn't one already (e.g., REPORT_START may have added "Generating Report")
      const hasInProgress = steps.some(s => s.status === 'IN_PROGRESS');
      if (!hasInProgress) {
        const nextStepNumber = steps.length + 1;
        this._researchStepsSignal.update(prev => [...prev, {
          stepNumber: nextStepNumber,
          name: payloadStr.replace('Completed research on:', 'Researching'),
          status: 'IN_PROGRESS' as ResearchModels.ResearchStep['status'],
          description: payloadStr
        }]);
      }
    } else if (steps.some(s => s.status === 'IN_PROGRESS')) {
      // There's already an IN_PROGRESS step — just update it with the new description
      const inProgressIdx = steps.findIndex(s => s.status === 'IN_PROGRESS');
      this._researchStepsSignal.update(prev => prev.map((s, i) => ({
        ...s,
        status: (i < inProgressIdx ? 'COMPLETED' : 'IN_PROGRESS') as ResearchModels.ResearchStep['status'],
        description: payloadStr
      })));
    } else {
      // No IN_PROGRESS step yet — create one for this new sub-topic being researched
      const idx = Math.max(0, steps.length - 1);
      this._researchStepsSignal.update(prev => prev.map((s, i) => ({
        ...s,
        status: (i < idx ? 'COMPLETED' : 'IN_PROGRESS') as ResearchModels.ResearchStep['status'],
        description: payloadStr
      })));
    }
  }

  private handleStepComplete(event: ResearchModels.StepCompleteSseEvent): void {
    const steps = this.researchSteps();

    // Strategy 1: If payload is a string, try to match against step names
    if (typeof event.payload === 'string') {
      const idx = steps.findIndex(s => s.name.toLowerCase().includes((event.payload as unknown as string).toLowerCase()));
      if (idx >= 0) {
        this._researchStepsSignal.update(prev => prev.map((s, i) =>
          i === idx ? { ...s, status: 'COMPLETED' as const } : s
        ));
        return;
      }
    }

    // Strategy 2: If payload is a number, treat it as an index or stepNumber
    if (typeof event.payload === 'number') {
      let matchedIdx = -1;
      if ((event.payload as unknown as number) >= 0 && (event.payload as unknown as number) < steps.length) {
        matchedIdx = event.payload as unknown as number;
      } else {
        const idx2 = steps.findIndex(s => s.stepNumber === (event.payload as unknown as number));
        if (idx2 >= 0) matchedIdx = idx2;
      }
      if (matchedIdx >= 0) {
        this._researchStepsSignal.update(prev => prev.map((s, i) =>
          i === matchedIdx ? { ...s, status: 'COMPLETED' as const } : s
        ));
        return;
      }
    }

    // Strategy 3: If payload is an object with a recognizable key (stepName, name, or index)
    if (typeof event.payload === 'object') {
      const obj = event.payload as Record<string, unknown>;
      let matchedIdx = -1;

      // Try matching by stepName field in the payload object
      const stepNameVal = obj['stepName'];
      if (typeof stepNameVal === 'string') {
        const idx3 = steps.findIndex(s => s.name.toLowerCase().includes(stepNameVal.toLowerCase()));
        if (idx3 >= 0) matchedIdx = idx3;
      }

      // Try matching by name field in the payload object (fallback)
      if (matchedIdx < 0) {
        const nameVal = obj['name'];
        if (typeof nameVal === 'string') {
          const idx4 = steps.findIndex(s => s.name.toLowerCase().includes(nameVal.toLowerCase()));
          if (idx4 >= 0) matchedIdx = idx4;
        }
      }

      // Try matching by index field in the payload object
      if (matchedIdx < 0) {
        const indexVal = obj['index'];
        if (typeof indexVal === 'number') {
          const idx5 = steps.findIndex(s => s.stepNumber === indexVal);
          if (idx5 >= 0) matchedIdx = idx5;
        }
      }

      // Try matching by stepNumber field in the payload object
      if (matchedIdx < 0) {
        const snVal = obj['stepNumber'];
        if (typeof snVal === 'number') {
          const idx6 = steps.findIndex(s => s.stepNumber === snVal);
          if (idx6 >= 0) matchedIdx = idx6;
        }
      }

      if (matchedIdx >= 0) {
        this._researchStepsSignal.update(prev => prev.map((s, i) =>
          i === matchedIdx ? { ...s, status: 'COMPLETED' as const } : s
        ));
        return;
      }
    }

    // Strategy 4: Fallback — mark the last IN_PROGRESS step as COMPLETED
    const inProgressIdx = steps.findIndex(s => s.status === 'IN_PROGRESS');
    if (inProgressIdx >= 0) {
      this._researchStepsSignal.update(prev => prev.map((s, i) =>
        i === inProgressIdx ? { ...s, status: 'COMPLETED' as const } : s
      ));
    }
  }

  private appendContent(content: string): void {
    this._reportContentSignal.update(r => r + content);
  }
}
