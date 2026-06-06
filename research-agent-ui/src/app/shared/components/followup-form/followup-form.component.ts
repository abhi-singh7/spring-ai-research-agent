import { Component, input, signal, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ResearchService } from '../../../core/services/research.service';

@Component({
  selector: 'followup-form',
  standalone: true,
  imports: [MatCardModule, FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatProgressSpinnerModule],
  template: `
    <mat-card class="followup-card">
      <mat-card-content>
        @if (answer()) {
          <!-- Show answer when available -->
          <div class="answer-display">
            <p><strong>Answer:</strong></p>
            <pre>{{ answer() }}</pre>
          </div>
        } @else {
          <!-- Show form for asking follow-up -->
          <form (ngSubmit)="onAsk()">
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Ask a follow-up question...</mat-label>
              <textarea matInput [(ngModel)]="question" rows="3" name="question"></textarea>
            </mat-form-field>

            <div class="followup-actions">
              @if (isLoading()) {
                <mat-spinner diameter="20"></mat-spinner>
              }
              <button mat-raised-button color="primary" type="submit" [disabled]="!question.trim() || isLoading()">
                Ask Follow-up
              </button>
            </div>
          </form>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 12px; }
    .followup-actions { display: flex; align-items: center; gap: 12px; }
    .answer-display { padding-top: 8px; border-top: 1px solid #e0e0e0; margin-top: 8px; }
    .answer-display pre { white-space: pre-wrap; font-family: inherit; color: #424242; background: #f5f5f5; padding: 12px; border-radius: 8px; margin: 8px 0; }
  `]
})
export class FollowUpFormComponent {
  sessionId = input.required<string>();

  question = '';
  answer = signal<string | null>(null);
  isLoading = signal(false);
  private researchService = inject(ResearchService);

  onAsk(): void {
    if (!this.question.trim()) return;

    this.isLoading.set(true);
    this.answer.set(null);

    this.researchService.submitFollowUp(this.sessionId(), this.question).pipe(
      takeUntilDestroyed()
    ).subscribe({
      next: (answer: string) => {
        this.answer.set(answer || 'No answer received.');
        this.isLoading.set(false);
        this.question = '';
      },
      error: () => {
        this.answer.set('Error submitting follow-up question. Please try again.');
        this.isLoading.set(false);
      }
    });
  }
}
