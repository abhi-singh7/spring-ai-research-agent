import { Component, inject, signal, OnInit, OnDestroy, computed } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';
import { StepListComponent } from '../../shared/components/step-list/step-list.component';
import { ReportViewerComponent } from '../../shared/components/report-viewer/report-viewer.component';
import { ResearchService } from '../../core/services/research.service';
import { trigger, transition, style, animate } from '@angular/animations';

@Component({
  selector: 'app-active-research',
  standalone: true,
  imports: [MatCardModule, MatProgressBarModule, MatButtonModule, RouterLink, StepListComponent, ReportViewerComponent, MatIconModule],
  animations: [
    trigger('fadeIn', [
      transition(':enter', [style({ opacity: 0, transform: 'translateY(-8px)' }), animate('300ms ease-out', style({ opacity: 1, transform: 'translateY(0)' }))]),
    ]),
  ],
  template: `
    <div class="active-research-container">
      <!-- Error notification -->
      @if (errorMessage()) {
        <mat-card class="error-banner" [@fadeIn]>
          <span>{{ errorMessage() }}</span>
          <button mat-icon-button (click)="dismissError()">
            <mat-icon>close</mat-icon>
          </button>
        </mat-card>
      }

      <!-- Back button -->
      @if (researchSession()) {
        <a routerLink="/research/history" class="back-link">
          <mat-icon>arrow_back</mat-icon>
          Back to History
        </a>
      }

      <!-- Status header -->
      @if (researchSession()) {
        <mat-card class="status-header" [@fadeIn]>
          <div class="status-info">
            <h2>{{ researchSession()!.topic }}</h2>
            <span class="status-chip" [class.processing]="researchSession()!.status === 'PROCESSING'"
                  [class.completed]="researchSession()!.status === 'COMPLETED'"
                  [class.failed]="researchSession()!.status === 'FAILED'"
                  [class.pending]="researchSession()!.status === 'PENDING'"
                  [class.cancelled]="researchSession()!.status === 'CANCELLED'">
              {{ getStatusLabel(researchSession()!.status) }}
            </span>
          </div>

          <!-- Progress bar with gradient animation during processing -->
          @if (isStreaming()) {
            <mat-progress-bar mode="determinate" [value]="progressPercent()" class="animated-progress"></mat-progress-bar>
          }
        </mat-card>

        <!-- Step-by-step progress -->
        <step-list [steps]="researchSteps()" [activeStepIndex]="activeStepIndex()"></step-list>

        <!-- Streaming content placeholder while waiting for first chunks -->
        @if (shouldDisplayStreamingContent() && !streamingContent()) {
          <mat-card class="streaming-placeholder" [@fadeIn]>
            <div class="spinner"></div>
            <p>Gathering findings...</p>
          </mat-card>
        }

        <!-- Streaming content or final report -->
        @if (shouldDisplayStreamingContent()) {
          <report-viewer [content]="streamingContent()" />
        } @else if (researchSession()!.status === 'COMPLETED' && researchSession()!.finalReport) {
          <report-viewer [content]="researchSession()!.finalReport!" />
        }

        <!-- Cancel button — only show when research is actively processing -->
        @if (researchSession()?.status === 'PROCESSING') {
          <button mat-raised-button color="warn" class="cancel-btn" [@fadeIn]>
            Cancel Research
          </button>
        }
      } @else {
        <p class="empty-state">Loading research session...</p>
      }
    </div>
  `,
  styles: [`
    .active-research-container { padding: 24px; max-width: 1000px; margin: 0 auto; }

    /* Back link */
    .back-link { display: inline-flex; align-items: center; gap: 6px; color: #6366f1; text-decoration: none; font-weight: 500; padding: 8px 0; margin-bottom: 16px; transition: all 0.2s ease; }
    .back-link:hover { opacity: 0.7; transform: translateX(-2px); }

    /* Status header */
    .status-header { margin-bottom: 24px; border-radius: 12px !important; overflow: hidden; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.08); }
    .status-info { display: flex; align-items: center; gap: 12px; padding: 16px 20px; }
    .status-info h2 { margin: 0; font-size: 1.25rem; font-weight: 700; color: #0f0f23 !important; flex: 1; }

    /* Status chip */
    .status-chip { padding: 4px 14px; border-radius: 20px; font-size: 0.8rem; font-weight: 500; letter-spacing: 0.3px; transition: all 0.3s ease; }
    .processing { background-color: #fff3e0; color: #ef6c00; animation: pulse-chip 2s infinite; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .pending { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    /* Dark mode status chip colors */
    @media (prefers-color-scheme: dark) {
      .status-info h2 { color: #e0e0e0; }
      .processing { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; box-shadow: 0 0 10px rgba(255, 183, 77, 0.15); }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; box-shadow: 0 0 10px rgba(102, 187, 106, 0.15); }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; box-shadow: 0 0 10px rgba(239, 83, 80, 0.15); }
      .pending { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

    /* Animated progress bar */
    .animated-progress::ng-deep .mat-progress-bar-fill::after { animation: progress-gradient 2s linear infinite; background-image: linear-gradient(90deg, transparent, rgba(99, 102, 241, 0.3), transparent); }

    /* Cancel button */
    .cancel-btn { margin-top: 16px; border-radius: 8px !important; font-weight: 500; text-transform: none; letter-spacing: 0.3px; transition: all 0.2s ease; }
    .cancel-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 12px rgba(239, 83, 80, 0.3); }

    /* Error banner */
    .error-banner { margin-bottom: 16px; padding: 12px 16px !important; background-color: #ffebee !important; color: #c62828 !important; display: flex; align-items: center; justify-content: space-between; border-radius: 8px !important; min-height: 48px; }
    .error-banner span { flex: 1; margin-right: 16px; word-break: break-word; }
    .error-banner button { flex-shrink: 0; }
    @media (prefers-color-scheme: dark) { .error-banner { background-color: rgba(239, 83, 80, 0.15) !important; color: #ef5350 !important; } }

    /* Streaming content placeholder */
    .streaming-placeholder { display: flex; align-items: center; gap: 12px; padding: 24px; border-radius: 12px !important; background-color: #f8f9ff !important; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.05); }
    .streaming-placeholder .spinner { width: 20px; height: 20px; border: 3px solid #e0e0e0; border-top-color: #6366f1; border-radius: 50%; animation: spin 1s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .streaming-placeholder p { color: #7c7c9a; margin: 0; font-style: italic; }

    /* Dark mode streaming placeholder */
    @media (prefers-color-scheme: dark) {
      .streaming-placeholder { background-color: rgba(99, 102, 241, 0.05) !important; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.1); }
      .streaming-placeholder .spinner { border-color: #424242; border-top-color: #6366f1; }
      .streaming-placeholder p { color: #9e9e9e; }
    }

    /* Empty state */
    .empty-state { text-align: center; color: #999; padding: 40px; font-size: 1rem; }

    /* Pulse animation for processing chip */
    @keyframes pulse-chip { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }

    /* Progress gradient animation */
    @keyframes progress-gradient { from { transform: translateX(-100%); } to { transform: translateX(100%); } }

    /* Fade in animation */
    @keyframes fadeIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
  `]
})
export class ActiveResearchComponent implements OnInit, OnDestroy {

  private route = inject(ActivatedRoute);
  private researchService = inject(ResearchService);

  sessionId!: string;

  private errorSub = new Subscription();
  errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId')!;
    this.loadSession();

    // Subscribe to errors from service
    this.errorSub = this.researchService.error$.subscribe(err => {
      this.errorMessage.set(err);
    });
  }

  ngOnDestroy(): void {
    this.researchService.disconnectSse();
    this.researchService.stopPolling();
    this.errorSub.unsubscribe();
  }

  // Delegate all signal state to the ResearchService
  researchSession = this.researchService.researchSession;
  isStreaming = this.researchService.isStreaming;
  progressPercent = this.researchService.progressPercent;
  researchSteps = this.researchService.researchSteps;
  activeStepIndex = this.researchService.activeStepIndex;

  // Streaming content signals
  shouldDisplayStreamingContent = this.researchService.shouldDisplayStreamingContent;
  streamingContent = computed(() => {
    const session = this.researchSession();
    if (session?.finalReport) return session.finalReport;
    return this.researchService.reportContent() || '';
  });

  dismissError(): void {
    this.errorMessage.set(null);
  }

  getStatusColor(status: string): string { return status.toLowerCase(); }
  getStatusLabel(status: string): string {
    const labels: Record<string, string> = {
      PENDING: 'Pending', PROCESSING: 'Processing...', COMPLETED: 'Completed',
      FAILED: 'Failed', CANCELLED: 'Cancelled'
    };
    return labels[status] || status;
  }

  private loadSession(): void {
    this.researchService.getHistoricalSession(this.sessionId).subscribe({
      next: (session) => {
        // Convert backend steps to frontend format and sync into signal
        const newSteps = (session as any)?.steps?.map((step: any, i: number) => ({
          stepNumber: step.orderIndex + 1,
          name: this.researchService.getStepName(step.type),
          status: step.status as 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED',
          description: step.content || ''
        })) ?? [];
        if (newSteps.length > 0) {
          this.researchService.setStepsFromHistory((session as any).steps);
        }
        this.researchService.researchSession.set(session);
        // Connect SSE only for sessions still in progress — COMPLETED sessions are already done and don't need streaming
        if (session.status === 'PROCESSING') {
          this.researchService.connectSse(this.sessionId);
        } else {
          this.researchService.isStreaming.set(false);
        }
      },
      error: () => {
        const session = this.researchSession();
        if (session) {
          this.errorMessage.set('Could not load research session. It may have been deleted.');
        } else {
          this.errorMessage.set('Failed to load the research session. Please try again.');
        }
      }
    });
  }

  onCancel(): void {
    this.researchService.cancelResearch(this.sessionId).subscribe({
      next: () => {},
      error: () => {}  // Ignore cancellation errors — session may already be cancelled
    });
  }
}
