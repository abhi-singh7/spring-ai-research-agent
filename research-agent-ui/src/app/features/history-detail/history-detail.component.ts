import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReportViewerComponent } from '../../shared/components/report-viewer/report-viewer.component';
import { FollowUpThreadComponent } from '../../shared/components/followup-thread/followup-thread.component';
import { StepListComponent } from '../../shared/components/step-list/step-list.component';
import { ResearchService } from '../../core/services/research.service';
import { trigger, transition, style, animate } from '@angular/animations';

@Component({
  selector: 'app-history-detail',
  standalone: true,
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatChipsModule, MatTooltipModule, RouterLink, ReportViewerComponent, FollowUpThreadComponent, StepListComponent],
  animations: [
    trigger('fadeIn', [
      transition(':enter', [style({ opacity: 0, transform: 'translateY(-8px)' }), animate('300ms ease-out', style({ opacity: 1, transform: 'translateY(0)' }))]),
    ]),
  ],
  template: `
    <div class="history-detail-container">
      <!-- Back button and session info -->
      @if (researchSession()) {
        <a routerLink="/research/history" class="back-link">
          <mat-icon>arrow_back</mat-icon>
          Back to History
        </a>

        <!-- Session info card -->
        <mat-card class="session-info" [@fadeIn]>
          <div class="info-content">
            <h2>{{ researchSession()!.topic }}</h2>
            <mat-chip class="status-chip" [matTooltip]="statusHint(researchSession()!.status)" [class.pending]="researchSession()!.status === 'PENDING'"
                  [class.processing]="researchSession()!.status === 'PROCESSING'"
                  [class.completed]="researchSession()!.status === 'COMPLETED'"
                  [class.failed]="researchSession()!.status === 'FAILED'"
                  [class.cancelled]="researchSession()!.status === 'CANCELLED'">
              {{ getStatusLabel(researchSession()!.status) }}
            </mat-chip>
          </div>
        </mat-card>

        <!-- Resume action — failed/cancelled sessions can continue from their stored steps -->
        @if (researchSession()!.status === 'FAILED' || researchSession()!.status === 'CANCELLED') {
          <div class="resume-section" [@fadeIn]>
            <button mat-raised-button class="mat-primary" [disabled]="resuming()" (click)="onResume()">
              <mat-icon>play_arrow</mat-icon>
              {{ resuming() ? 'Resuming…' : 'Resume research' }}
            </button>
            @if (resumeError()) {
              <span class="resume-error">{{ resumeError() }}</span>
            }
          </div>
        }

        <!-- Steps list — only for completed sessions -->
        @if (researchSession()!.status === 'COMPLETED') {
          <step-list [steps]="researchSteps" [activeStepIndex]="activeStepIndex" class="steps-section" />
        }

        <!-- Report viewer — only if report content exists -->
        @if (researchSession()!.finalReport) {
          <div [@fadeIn] class="report-section">
            <div class="report-actions">
              <a mat-stroked-button routerLink="/research/history/{{ researchSession()!.id }}/report">
                <mat-icon>open_in_full</mat-icon> Open full page
              </a>
            </div>
            <report-viewer [content]="researchSession()!.finalReport!" />
          </div>
        }

        <!-- Follow-up conversation thread — only for completed sessions -->
        @if (researchSession()!.status === 'COMPLETED') {
          <followup-thread [sessionId]="researchSession()!.id || ''"
                           [initialExchanges]="researchSession()!.followUps || []" class="followup-section" />
        }
      } @else if (isLoading()) {
        <p class="empty-state">Loading session...</p>
      } @else if (hasError()) {
        <div class="empty-state error-message" [@fadeIn]>
          <p>Failed to load session. Please try again.</p>
          <button mat-raised-button class="mat-primary" (click)="loadSession()">Retry</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .history-detail-container { padding: 24px; max-width: 1000px; margin: 0 auto; }

    /* Back link — styled as a Material action, not a hyperlink */
    .back-link { display: inline-flex; align-items: center; gap: 6px; color: var(--rp-on-surface); text-decoration: none; font-weight: 500; padding: 8px 14px; margin-bottom: 16px; transition: opacity var(--rp-motion-fast) var(--rp-easing-standard), transform var(--rp-motion-fast) var(--rp-easing-standard), background-color var(--rp-motion-fast) var(--rp-easing-standard), border-color var(--rp-motion-fast) var(--rp-easing-standard); border-left: 3px solid transparent; }
    .back-link:hover { opacity: 0.75; transform: translateX(-4px); border-left-color: var(--rp-primary); background-color: var(--rp-brand-08); padding-left: 12px; }
    .back-link mat-icon { font-size: 20px; width: 20px; height: 20px; line-height: 20px; }

    /* Session info card */
    .session-info {
      margin-bottom: 24px;
      --mdc-elevated-card-container-shape: 12px;
      overflow: hidden;
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-1);
    }
    .info-content { display: flex; align-items: center; gap: 16px; padding: 16px 20px; }
    .info-content h2 { margin: 0; font-size: 1.35rem; font-weight: 600; color: var(--rp-on-surface) !important; flex: 1; }

    /* Status chip (Material mat-chip; colors through the chip's own tokens) */
    .status-chip {
      --mdc-chip-container-height: 28px;
      --mdc-chip-container-shape-radius: 14px;
      --mdc-chip-label-text-size: 0.8rem;
      --mdc-chip-label-text-weight: 500;
      flex-shrink: 0;
    }
    .status-chip.pending { --mdc-chip-elevated-container-color: var(--rp-warning-container); --mdc-chip-label-text-color: var(--rp-on-warning-container); }
    .status-chip.processing { --mdc-chip-elevated-container-color: var(--rp-primary-container); --mdc-chip-label-text-color: var(--rp-on-primary-container); }
    .status-chip.completed { --mdc-chip-elevated-container-color: var(--rp-success-container); --mdc-chip-label-text-color: var(--rp-on-success-container); }
    .status-chip.failed { --mdc-chip-elevated-container-color: var(--rp-error-container); --mdc-chip-label-text-color: var(--rp-on-error-container); }
    .status-chip.cancelled { --mdc-chip-elevated-container-color: var(--rp-surface); --mdc-chip-label-text-color: var(--rp-on-surface-variant); }

    /* Resume section */
    .resume-section { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    .resume-section button { border-radius: 8px !important; font-weight: 500; text-transform: none; letter-spacing: 0.3px; }
    .resume-error { color: var(--rp-error); font-size: 0.9rem; }

    /* Sections */
    .steps-section { margin-bottom: 24px; }
    .report-section { margin-bottom: 24px; }
    .followup-section { margin-top: 8px; }

    /* Full-page document action */
    .report-actions { display: flex; justify-content: flex-end; margin-bottom: 8px; }
    .report-actions button { color: var(--rp-primary); font-weight: 500; }
    .report-actions mat-icon { font-size: 18px; width: 18px; height: 18px; }

    /* Empty state */
    .empty-state { text-align: center; padding: 40px; color: var(--rp-on-surface-variant); font-size: 1rem; }
    .error-message p { margin-bottom: 16px; }

    /* Fade in animation */
    @keyframes fadeIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
  `]
})
export class HistoryDetailComponent {

  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private researchService = inject(ResearchService);

  sessionId!: string;
  isLoading = signal(true);
  hasError = signal(false);
  resuming = signal(false);
  resumeError = signal<string | null>(null);

  ngOnInit(): void {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId')!;
    this.loadSession();
  }

  ngOnDestroy(): void {}

  researchSession = this.researchService.researchSession;

  // Extract steps from the response for display — transform to StepListComponent format
  get researchSteps(): any[] {
    const session = this.researchSession();
    return (session?.steps || []).map((step) => ({
      stepNumber: (step as any).orderIndex + 1,
      name: this.getStepTypeName((step as any).type),
      status: (step as any).status,
      description: (step as any).content
    }));
  }

  getStepTypeName(type: string): string {
    const names: Record<string, string> = {
      BREAKDOWN: 'Breakdown', SUBTOPIC: 'Sub-topic', QUALITY_EVAL: 'Quality Check',
      FINAL_REPORT: 'Final Report', SEARCH: 'Search', READ: 'Read', SYNTHESIS: 'Synthesis'
    };
    return names[type] || type;
  }

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
      PROCESSING: 'Still running — open the live session to watch its steps',
      COMPLETED: 'Report available',
      FAILED: 'Failed — resume this session to run it again',
      CANCELLED: 'Cancelled — resume this session to run it again'
    };
    return hints[status] || status;
  }

  // Get index of last completed step (for highlighting)
  get activeStepIndex(): number {
    const steps = this.researchSteps;
    for (let i = steps.length - 1; i >= 0; i--) {
      if (steps[i].status === 'COMPLETED') return i;
    }
    return -1;
  }

  getStatusClass(status: string): string {
    const classes: Record<string, string> = {
      PENDING: 'pending', PROCESSING: 'processing', COMPLETED: 'completed', FAILED: 'failed', CANCELLED: 'cancelled'
    };
    return classes[status] || '';
  }

  /**
   * Resume a failed/cancelled session: the server flips it back to PROCESSING and reconnects
   * the SSE stream; on success we jump to the live view which renders the ongoing progress.
   */
  onResume(): void {
    const id = this.sessionId;
    this.resuming.set(true);
    this.resumeError.set(null);
    this.researchService.resumeAndConnect(id).subscribe({
      next: () => {
        this.router.navigate(['/research', id]);
      },
      error: (err) => {
        this.resuming.set(false);
        console.error('[HistoryDetailComponent] Resume failed:', err);
        this.resumeError.set(err?.status === 409
          ? 'This session can no longer be resumed.'
          : 'Could not resume the research. Please try again.');
      }
    });
  }

  loadSession(): void {
    this.isLoading.set(true);
    this.hasError.set(false);
    // Clear the shared session state before fetching — otherwise the template (which renders
    // `researchSession()` first) would show the previously-viewed session's topic/report while
    // this request is in flight, making it look like a different history entry was loaded.
    this.researchService.researchSession.set(null);
    const requestedId = this.sessionId;
    this.researchService.getHistoricalSession(requestedId).subscribe({
      next: (session) => {
        // Ignore stale responses if the user has since navigated to a different session
        if (requestedId !== this.sessionId) return;
        this.researchService.researchSession.set(session);
        this.isLoading.set(false);
      },
      error: (error) => {
        if (requestedId !== this.sessionId) return;
        console.error('[HistoryDetailComponent] Failed to load session:', error);
        this.hasError.set(true);
        this.isLoading.set(false);
      }
    });
  }

  goBack(): void {}
}
