import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { ReportViewerComponent } from '../../shared/components/report-viewer/report-viewer.component';
import { FollowUpFormComponent } from '../../shared/components/followup-form/followup-form.component';
import { StepListComponent } from '../../shared/components/step-list/step-list.component';
import { ResearchService } from '../../core/services/research.service';

@Component({
  selector: 'app-history-detail',
  standalone: true,
  imports: [MatButtonModule, MatCardModule, RouterLink, ReportViewerComponent, FollowUpFormComponent, StepListComponent],
  template: `
    <div class="history-detail-container">
      <!-- Back button and session info -->
      @if (researchSession()) {
        <a routerLink="/research/history" mat-button>← Back to History</a>

        <!-- Session info -->
        <mat-card class="session-info">
          <h2>{{ researchSession()!.topic }}</h2>
          <span class="status-chip" [class.pending]="researchSession()!.status === 'PENDING'"
                [class.processing]="researchSession()!.status === 'PROCESSING'"
                [class.completed]="researchSession()!.status === 'COMPLETED'"
                [class.failed]="researchSession()!.status === 'FAILED'"
                [class.cancelled]="researchSession()!.status === 'CANCELLED'">
            {{ researchSession()!.status }}
          </span>
        </mat-card>

        <!-- Steps list — only for completed sessions -->
        @if (researchSession()!.status === 'COMPLETED') {
          <step-list [steps]="researchSteps" [activeStepIndex]="activeStepIndex" />
        }

        <!-- Report viewer — only if report content exists -->
        @if (researchSession()!.finalReport) {
          <report-viewer [content]="researchSession()!.finalReport!" />
        }

        <!-- Follow-up form — only for completed sessions -->
        @if (researchSession()!.status === 'COMPLETED') {
          <followup-form [sessionId]="researchSession()!.id || ''" />
        }
      } @else if (isLoading()) {
        <p class="empty-state">Loading session...</p>
      } @else if (hasError()) {
        <div class="empty-state error-message">
          <p>Failed to load session. Please try again.</p>
          <button mat-raised-button color="primary" (click)="loadSession()">Retry</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .history-detail-container { padding: 24px; max-width: 1000px; margin: 0 auto; }
    .session-info { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    .status-chip { padding: 2px 8px; border-radius: 12px; font-size: 0.75rem; }
    .pending { background-color: #fff3e0; color: #ef6c00; }
    .processing { background-color: #e3f2fd; color: #1565c0; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .cancelled { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    @media (prefers-color-scheme: dark) {
      .pending { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; }
      .processing { background-color: rgba(66, 165, 245, 0.2); color: #42a5f5; }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; }
      .cancelled { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

    .empty-state { text-align: center; padding: 40px; }
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
