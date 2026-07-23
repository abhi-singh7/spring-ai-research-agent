import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ReportViewerComponent } from '../../shared/components/report-viewer/report-viewer.component';
import { FollowUpFormComponent } from '../../shared/components/followup-form/followup-form.component';
import { StepListComponent } from '../../shared/components/step-list/step-list.component';
import { ResearchService } from '../../core/services/research.service';
import { trigger, transition, style, animate } from '@angular/animations';

@Component({
  selector: 'app-history-detail',
  standalone: true,
  imports: [MatButtonModule, MatCardModule, MatIconModule, RouterLink, ReportViewerComponent, FollowUpFormComponent, StepListComponent],
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
            <span class="status-chip" [class.pending]="researchSession()!.status === 'PENDING'"
                  [class.processing]="researchSession()!.status === 'PROCESSING'"
                  [class.completed]="researchSession()!.status === 'COMPLETED'"
                  [class.failed]="researchSession()!.status === 'FAILED'"
                  [class.cancelled]="researchSession()!.status === 'CANCELLED'">
              {{ getStatusLabel(researchSession()!.status) }}
            </span>
          </div>
        </mat-card>

        <!-- Steps list — only for completed sessions -->
        @if (researchSession()!.status === 'COMPLETED') {
          <step-list [steps]="researchSteps" [activeStepIndex]="activeStepIndex" class="steps-section" />
        }

        <!-- Report viewer — only if report content exists -->
        @if (researchSession()!.finalReport) {
          <div [@fadeIn] class="report-section">
            <report-viewer [content]="researchSession()!.finalReport!" />
          </div>
        }

        <!-- Follow-up form — only for completed sessions -->
        @if (researchSession()!.status === 'COMPLETED') {
          <followup-form [sessionId]="researchSession()!.id || ''" class="followup-section" />
        }
      } @else if (isLoading()) {
        <p class="empty-state">Loading session...</p>
      } @else if (hasError()) {
        <div class="empty-state error-message" [@fadeIn]>
          <p>Failed to load session. Please try again.</p>
          <button mat-raised-button color="primary" (click)="loadSession()">Retry</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .history-detail-container { padding: 24px; max-width: 1000px; margin: 0 auto; }

    /* Back link */
    .back-link { display: inline-flex; align-items: center; gap: 6px; color: #6366f1; text-decoration: none; font-weight: 500; padding: 8px 0; margin-bottom: 16px; transition: all 0.2s ease; }
    .back-link:hover { opacity: 0.7; transform: translateX(-2px); }
    .back-link mat-icon { font-size: 20px; width: 20px; height: 20px; line-height: 20px; }

    /* Session info card */
    .session-info { margin-bottom: 24px; border-radius: 12px !important; overflow: hidden; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.08); }
    .info-content { display: flex; align-items: center; gap: 16px; padding: 16px 20px; }
    .info-content h2 { margin: 0; font-size: 1.35rem; font-weight: 600; color: #1a1a2e; flex: 1; }

    /* Status chip */
    .status-chip { padding: 4px 14px; border-radius: 20px; font-size: 0.8rem; font-weight: 500; letter-spacing: 0.3px; transition: all 0.3s ease; }
    .pending { background-color: #fff3e0; color: #ef6c00; }
    .processing { background-color: #e3f2fd; color: #1565c0; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .cancelled { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    /* Dark mode status chip colors */
    @media (prefers-color-scheme: dark) {
      .info-content h2 { color: #e0e0e0; }
      .pending { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; box-shadow: 0 0 8px rgba(255, 183, 77, 0.1); }
      .processing { background-color: rgba(66, 165, 245, 0.2); color: #42a5f5; box-shadow: 0 0 8px rgba(66, 165, 245, 0.1); }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; box-shadow: 0 0 8px rgba(102, 187, 106, 0.1); }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; box-shadow: 0 0 8px rgba(239, 83, 80, 0.1); }
      .cancelled { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

    /* Sections */
    .steps-section { margin-bottom: 24px; }
    .report-section { margin-bottom: 24px; }
    .followup-section { margin-top: 8px; }

    /* Empty state */
    .empty-state { text-align: center; padding: 40px; color: #999; font-size: 1rem; }
    .error-message p { margin-bottom: 16px; }

    /* Dark mode empty state */
    @media (prefers-color-scheme: dark) {
      .empty-state { color: #9e9e9e; }
    }

    /* Fade in animation */
    @keyframes fadeIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
  `]
})
export class HistoryDetailComponent {

  private route = inject(ActivatedRoute);
  private researchService = inject(ResearchService);

  sessionId!: string;
  isLoading = signal(true);
  hasError = signal(false);

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
      BREAKDOWN: 'Breakdown', SUBTOPIC: 'Sub-topic', FINAL_REPORT: 'Final Report',
      SEARCH: 'Search', READ: 'Read', SYNTHESIS: 'Synthesis'
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

  loadSession(): void {
    this.isLoading.set(true);
    this.hasError.set(false);
    this.researchService.getHistoricalSession(this.sessionId).subscribe({
      next: (session) => {
        this.researchService.researchSession.set(session);
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('[HistoryDetailComponent] Failed to load session:', error);
        this.hasError.set(true);
        this.isLoading.set(false);
      }
    });
  }

  goBack(): void {}
}
