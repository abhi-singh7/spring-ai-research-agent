import { Injectable, signal, computed, inject, DestroyRef } from '@angular/core';
import { HttpClient } from '@angular/common/http';
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

  // Derived State via Computed Signals
  readonly progressPercent = computed(() => {
    const steps = this.researchSteps();
    if (!steps.length) return 0;
    const completedCount = steps.filter(s => s.status === 'COMPLETED').length;
    const hasInProgress = steps.some(s => s.status === 'IN_PROGRESS');
    return Math.round(((completedCount + (hasInProgress ? 0.5 : 0)) / steps.length) * 100);
  });

  readonly activeStepIndex = computed(() => {
    const steps = this.researchSteps();
    const idx = steps.findIndex(s => s.status === 'IN_PROGRESS');
    return idx >= 0 ? idx : -1;
  });

  // SSE Connection (Primary method)
  private sseSource: EventSource | null = null;

  /** Connect to the SSE stream for a given session */
  connectSse(sessionId: string, streamUrl?: string): void {
    this.disconnectSse();

    const url = streamUrl || `${this.baseUrl}/stream/${sessionId}`;
    this.sseSource = new EventSource(url);
    this.isStreaming.set(true);

    // PROGRESS events - update the active step and progress
    this.sseSource.addEventListener('PROGRESS', (event) => {
      const data: ResearchModels.ProgressSseEvent = JSON.parse(event.data);
      this.handleProgress(data);
    });

    // CONTENT events - streaming text content as it arrives
    this.sseSource.addEventListener('CONTENT', (event) => {
      const data: ResearchModels.ContentSseEvent = JSON.parse(event.data);
      this.appendContent(data.payload);
    });

    // REPORT_CHUNK events - accumulate the final report
    this.sseSource.addEventListener('REPORT_CHUNK', (event) => {
      const data: ResearchModels.ReportChunkSseEvent = JSON.parse(event.data);
      this._reportContentSignal.update(content => content + data.payload);
    });

    // REPORT_DONE events - full report is ready
    this.sseSource.addEventListener('REPORT_DONE', (event) => {
      const data: ResearchModels.ReportDoneSseEvent = JSON.parse(event.data);
      this._reportContentSignal.set(data.payload);
      this.researchSession.update(s => s ? ({ ...s, status: 'COMPLETED' }) : null);
    });

    // STEP_COMPLETE events - mark a step as complete
    this.sseSource.addEventListener('STEP_COMPLETE', (event) => {
      const data: ResearchModels.StepCompleteSseEvent = JSON.parse(event.data);
      this.handleStepComplete(data);
    });

    // ERROR events - handle errors from the backend
    this.sseSource.addEventListener('ERROR', (event) => {
      const data: ResearchModels.ErrorSseEvent = JSON.parse(event.data);
      this.errorSubject.next(data.payload);
      this.researchSession.update(s => s ? ({ ...s, status: 'FAILED' }) : null);
    });

    // REPORT_START events - report generation has begun
    this.sseSource.addEventListener('REPORT_START', (event) => {
      const data: ResearchModels.ReportStartSseEvent = JSON.parse(event.data);
      const steps = this.researchSteps();
      if (!steps.some(s => s.name === 'Generating Report')) {
        this._researchStepsSignal.update(prev => [
          ...prev,
          { stepNumber: prev.length + 1, name: 'Generating Report', status: 'IN_PROGRESS' }
        ]);
      } else {
        const steps = this.researchSteps();
        const idx = steps.findIndex(s => s.name === 'Generating Report');
        if (idx >= 0) {
          this._researchStepsSignal.update(prev => prev.map((s, i) =>
            i === idx ? { ...s, status: 'IN_PROGRESS' } : s
          ));
        }
      }
    });

    // Open event - connection established
    this.sseSource.addEventListener('open', () => {
      console.log('[ResearchService] SSE connected for session:', sessionId);
    });

    // Error / reconnect handling
    const onError = () => {
      if (this.sseSource && this.sseSource.readyState === EventSource.CLOSED) {
        console.warn('[ResearchService] SSE connection closed for session:', sessionId);
        this.isStreaming.set(false);
        const currentSession = this.researchSession();
        if (currentSession && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(currentSession.status)) {
          this.errorSubject.next('SSE connection lost — session may still be running.');
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
    if (this.sseSource) {
      this.sseSource.close();
      this.sseSource = null;
      this.isStreaming.set(false);
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
    if (this.pollingTimer) {
      clearInterval(this.pollingTimer);
      this.pollingTimer = null;
    }
  }

  private pollStatus(sessionId: string): void {
    this.getStatus(sessionId).subscribe({
      next: (session) => {
        this.researchSession.set(session);
        if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status)) {
          this.stopPolling();
          this.isStreaming.set(false);
        }
      },
      error: () => {}  // Ignore transient polling errors — next poll will retry
    });
  }

  // REST API Methods
  startResearch(request: ResearchModels.ResearchStartRequest): Observable<ResearchModels.ResearchSession> {
    return new Observable(observer => {
      this.http.post<ResearchModels.ResearchSession>(this.baseUrl, request).subscribe({
        next: session => {
          // Try SSE first; fall back to polling if connection fails
          try {
            this.connectSse(session.id);
          } catch {
            this.startPolling(session.id);
          }
          observer.next(session);
        },
        error: err => observer.error(err)
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

  getHistoricalSession(sessionId: string): Observable<ResearchModels.ResearchSession> {
    return this.http.get<ResearchModels.ResearchSession>(`${this.baseUrl}/history/${sessionId}`);
  }

  submitFollowUp(sessionId: string, question: string): Observable<string> {
    const request: ResearchModels.FollowUpRequest = { question };
    return this.http.post<string>(`${this.baseUrl}/${sessionId}/followup`, request);
  }

  // SSE Event Handlers (private)
  private handleProgress(event: ResearchModels.ProgressSseEvent): void {
    const steps = this.researchSteps();
    if (!steps.length) return;  // Will be updated by REPORT_START or other events

    // Update current step as IN_PROGRESS, previous ones as COMPLETED
    const idx = Math.max(0, steps.length - 1);
    this._researchStepsSignal.update(prev => prev.map((s, i) => ({
      ...s,
      status: (i < idx ? 'COMPLETED' : 'IN_PROGRESS') as ResearchModels.ResearchStep['status'],
      description: event.payload
    })));
  }

  private handleStepComplete(event: ResearchModels.StepCompleteSseEvent): void {
    this._researchStepsSignal.update(prev => prev.map(s =>
      s.name.toLowerCase().includes(JSON.stringify(event.payload).toLowerCase())
        ? { ...s, status: 'COMPLETED' as const }
        : s
    ));
  }

  private appendContent(content: string): void {
    this._reportContentSignal.update(r => r + content);
  }
}
