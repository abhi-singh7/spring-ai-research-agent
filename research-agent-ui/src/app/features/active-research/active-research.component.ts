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

@Component({
  selector: 'app-active-research',
  standalone: true,
  imports: [MatCardModule, MatProgressBarModule, MatButtonModule, RouterLink, StepListComponent, ReportViewerComponent, MatIconModule],
  template: `
    <div class="active-research-container">
      <!-- Error notification -->
      @if (errorMessage()) {
        <mat-card class="error-banner">
          <span>{{ errorMessage() }}</span>
          <button mat-icon-button (click)="dismissError()">
            <mat-icon>close</mat-icon>
          </button>
        </mat-card>
      }

      <!-- Back button -->
      @if (researchSession()) {
        <a routerLink="/research/history" mat-button>← Back to History</a>
      }

      <!-- Status header -->
      @if (researchSession()) {
        <mat-card class="status-header">
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

          <!-- Progress bar -->
          @if (isStreaming()) {
            <mat-progress-bar mode="determinate" [value]="progressPercent()"></mat-progress-bar>
          }
        </mat-card>

        <!-- Step-by-step progress -->
        <step-list [steps]="researchSteps()" [activeStepIndex]="activeStepIndex()"></step-list>

        <!-- Streaming content placeholder while waiting for first chunks -->
        @if (shouldDisplayStreamingContent() && !streamingContent()) {
          <mat-card class="streaming-placeholder">
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

        <!-- Cancel button — shown while running -->
        @if (isStreaming()) {
          <button mat-raised-button color="warn" class="cancel-btn" (click)="onCancel()">
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
    .status-header { margin-bottom: 24px; }
    .status-info { display: flex; align-items: center; gap: 12px; }
    .status-chip { padding: 4px 12px; border-radius: 16px; font-size: 0.85rem; }
    .processing { background-color: #fff3e0; color: #ef6c00; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .pending { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    /* Dark mode status chip colors */
    @media (prefers-color-scheme: dark) {
      .processing { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; }
      .pending { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

    .cancel-btn { margin-top: 16px; }
    .empty-state { text-align: center; color: #999; padding: 40px; }

    /* Error banner */
    .error-banner { margin-bottom: 16px; padding: 8px 16px !important; background-color: #ffebee !important; color: #c62828 !important; display: flex; align-items: center; justify-content: space-between; }
    @media (prefers-color-scheme: dark) { .error-banner { background-color: rgba(239, 83, 80, 0.15) !important; color: #ef5350 !important; } }

    /* Streaming content placeholder */
    .streaming-placeholder { display: flex; align-items: center; gap: 12px; padding: 24px; }
    .streaming-placeholder .spinner { width: 20px; height: 20px; border: 3px solid #e0e0e0; border-top-color: #757575; border-radius: 50%; animation: spin 1s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .streaming-placeholder p { color: #9e9e9e; margin: 0; font-style: italic; }

    /* Dark mode streaming placeholder */
    @media (prefers-color-scheme: dark) {
      .streaming-placeholder .spinner { border-color: #424242; border-top-color: #bdbdbd; }
      .streaming-placeholder p { color: #757575; }
    }
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
    this.researchService.getStatus(this.sessionId).subscribe({
      next: (session) => {
        this.researchService.researchSession.set(session);
        // Connect SSE if the session is still in progress
        if (['PROCESSING', 'COMPLETED'].includes(session.status)) {
          this.researchService.connectSse(this.sessionId);
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
