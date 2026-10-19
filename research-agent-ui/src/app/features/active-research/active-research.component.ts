import { Component, inject, signal, OnInit, OnDestroy, computed } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subscription } from 'rxjs';
import { StepListComponent } from '../../shared/components/step-list/step-list.component';
import { ReportViewerComponent } from '../../shared/components/report-viewer/report-viewer.component';
import { ResearchService } from '../../core/services/research.service';
import { trigger, transition, style, animate } from '@angular/animations';

@Component({
  selector: 'app-active-research',
  standalone: true,
  imports: [MatCardModule, MatProgressBarModule, MatButtonModule, RouterLink, StepListComponent, ReportViewerComponent, MatIconModule, MatChipsModule, MatTooltipModule, MatProgressSpinnerModule],
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
            <mat-chip class="status-chip" [matTooltip]="statusHint(researchSession()!.status)" [class.processing]="researchSession()!.status === 'PROCESSING'"
                  [class.completed]="researchSession()!.status === 'COMPLETED'"
                  [class.failed]="researchSession()!.status === 'FAILED'"
                  [class.pending]="researchSession()!.status === 'PENDING'"
                  [class.cancelled]="researchSession()!.status === 'CANCELLED'">
              {{ getStatusLabel(researchSession()!.status) }}
            </mat-chip>

            @if (researchSession()?.status === 'FAILED') {
              <button mat-raised-button class="mat-primary resume-btn" [disabled]="resuming()" (click)="onResume()">
                <mat-icon>play_arrow</mat-icon>
                {{ resuming() ? 'Resuming…' : 'Resume Research' }}
              </button>
            }
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
            <mat-progress-spinner class="placeholder-spinner" mode="indeterminate" diameter="20" strokeWidth="3" />
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
          <button mat-raised-button class="mat-warn cancel-btn" [@fadeIn] (click)="onCancel()">
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
    .back-link { display: inline-flex; align-items: center; gap: 6px; color: var(--rp-primary); text-decoration: none; font-weight: 500; padding: 8px 0; margin-bottom: 16px; transition: opacity var(--rp-motion-fast) var(--rp-easing-standard), transform var(--rp-motion-fast) var(--rp-easing-standard); }
    .back-link:hover { opacity: 0.7; transform: translateX(-2px); }

    /* Resume button */
  .resume-btn { --mdc-protected-button-container-shape: 8px; font-weight: 500; text-transform: none; letter-spacing: 0.3px; }

  /* Status header */
    .status-header {
      margin-bottom: 24px;
      --mdc-elevated-card-container-shape: 12px;
      overflow: hidden;
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-1);
    }
    .status-info { display: flex; align-items: center; gap: 12px; padding: 16px 20px; }
    .status-info h2 { margin: 0; font-size: 1.25rem; font-weight: 700; color: var(--rp-on-surface) !important; flex: 1; }

    /* Status chip (Material mat-chip; colors through the chip's own tokens) */
    .status-chip {
      --mdc-chip-container-height: 28px;
      --mdc-chip-container-shape-radius: 14px;
      --mdc-chip-label-text-size: 0.8rem;
      --mdc-chip-label-text-weight: 500;
      flex-shrink: 0;
    }
    .status-chip.processing { --mdc-chip-elevated-container-color: var(--rp-primary-container); --mdc-chip-label-text-color: var(--rp-on-primary-container); animation: pulse-chip 2s infinite; }
    .status-chip.completed { --mdc-chip-elevated-container-color: var(--rp-success-container); --mdc-chip-label-text-color: var(--rp-on-success-container); }
    .status-chip.failed { --mdc-chip-elevated-container-color: var(--rp-error-container); --mdc-chip-label-text-color: var(--rp-on-error-container); }
    .status-chip.pending { --mdc-chip-elevated-container-color: var(--rp-surface); --mdc-chip-label-text-color: var(--rp-on-surface-variant); }
    .status-chip.cancelled { --mdc-chip-elevated-container-color: var(--rp-surface); --mdc-chip-label-text-color: var(--rp-outline); }

    /* Animated progress bar */
    .animated-progress::ng-deep .mat-progress-bar-fill::after { animation: progress-gradient 2s linear infinite; background-image: linear-gradient(90deg, transparent, var(--rp-brand-25), transparent); }

    /* Cancel button */
    .cancel-btn {
      margin-top: 16px;
      /* Material protected-button API — hover raises elevation via its own token,
         so the hand-drawn :hover transform/shadow rule is gone. */
      --mdc-protected-button-container-shape: 8px;
      --mdc-protected-button-hover-container-elevation-shadow: var(--mat-app-elevation-shadow-level-4);
      --mdc-protected-button-pressed-container-elevation-shadow: var(--mat-app-elevation-shadow-level-0);
      font-weight: 500;
      text-transform: none;
      letter-spacing: 0.3px;
    }

    /* Error banner */
    .error-banner { margin-bottom: 16px; padding: 12px 16px !important; background-color: var(--rp-error-container) !important; color: var(--rp-on-error-container) !important; display: flex; align-items: center; justify-content: space-between; border-radius: 8px !important; min-height: 48px; }
    .error-banner span { flex: 1; margin-right: 16px; word-break: break-word; }
    .error-banner button { flex-shrink: 0; }

    /* Streaming content placeholder */
    .streaming-placeholder {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 24px;
      --mdc-elevated-card-container-shape: 12px;
      --mdc-elevated-card-container-color: var(--rp-info-soft);
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-1);
    }
    .placeholder-spinner { flex-shrink: 0; margin-right: 12px; }
    .streaming-placeholder p { color: var(--rp-on-surface-variant); margin: 0; font-style: italic; }

    /* Empty state */
    .empty-state { text-align: center; color: var(--rp-on-surface-variant); padding: 40px; font-size: 1rem; }

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
  resuming = signal(false);

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

  /** Hover affordance for the status chip: what the user can actually do with this session. */
  statusHint(status: string): string {
    const hints: Record<string, string> = {
      PENDING: 'Queued — the pipeline has not started yet',
      PROCESSING: 'Research running — steps and live report appear below',
      COMPLETED: 'Report available',
      FAILED: 'Failed — resume this session to run it again',
      CANCELLED: 'Cancelled — resume this session to run it again'
    };
    return hints[status] || status;
  }

  private loadSession(): void {
    // Clear the shared session state before fetching so this page never renders another
    // session's stale data while its own request is in flight.
    this.researchService.researchSession.set(null);
    const requestedId = this.sessionId;
    this.researchService.getHistoricalSession(requestedId).subscribe({
      next: (session) => {
        // Ignore stale responses if the user has since navigated to a different session
        if (requestedId !== this.sessionId) return;
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

  /**
   * Resume a failed session in place: the server flips it back to PROCESSING and reconnects the
   * SSE stream; the local session snapshot is refreshed so header/banner reflect the new state.
   */
  onResume(): void {
    const id = this.sessionId;
    this.resuming.set(true);
    this.researchService.resumeAndConnect(id).subscribe({
      next: () => {
        this.resuming.set(false);
        this.errorMessage.set(null);
        this.researchSession.update(s => s ? { ...s, status: 'PROCESSING', finalReport: undefined } : s);
      },
      error: (err) => {
        this.resuming.set(false);
        console.error('[ActiveResearchComponent] Resume failed:', err);
        this.errorMessage.set(err?.status === 409
          ? 'This session can no longer be resumed.'
          : 'Could not resume the research. Please try again.');
      }
    });
  }
}
