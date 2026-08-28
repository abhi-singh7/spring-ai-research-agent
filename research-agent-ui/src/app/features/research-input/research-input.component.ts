import { Component, inject, signal } from '@angular/core';
import { trigger, transition, style, animate } from '@angular/animations';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { Router, RouterLink } from '@angular/router';
import { ResearchService } from '../../core/services/research.service';

@Component({
  selector: 'app-research-input',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatChipsModule,
    RouterLink
  ],
  animations: [
    trigger('fadeIn', [
      transition(':enter', [style({ opacity: 0, transform: 'translateY(-8px)' }), animate('300ms ease-out', style({ opacity: 1, transform: 'translateY(0)' }))]),
    ]),
  ],
  template: `
    <div class="research-input-container">
      <mat-card class="input-card" [@fadeIn]>
        <div class="card-header">
          <h1>New Research</h1>
          <p>Enter a topic and let the AI research it thoroughly across multiple sub-topics.</p>
        </div>

        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="onSubmit()">

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Research Topic</mat-label>
              <input
                matInput
                formControlName="topic"
                placeholder="e.g., Impact of AI on Software Engineering"
              />

              @if (form.get('topic')?.hasError('required')) {
                <mat-error>Topic is required</mat-error>
              }
            </mat-form-field>

            <div class="advanced-options">
              <mat-form-field appearance="outline" class="half-width">
                <mat-label>Max Iterations (optional)</mat-label>
                <input
                  matInput
                  type="number"
                  formControlName="maxIterations"
                  min="1"
                  max="50"
                />
                <mat-hint>Controls research depth</mat-hint>
              </mat-form-field>

              <mat-form-field appearance="outline" class="half-width">
                <mat-label>Sub-topics (optional)</mat-label>
                <input
                  matInput
                  type="number"
                  formControlName="subTopicCount"
                  min="1"
                  max="20"
                />
                <mat-hint>Number of parallel topics</mat-hint>
              </mat-form-field>
            </div>

            <button
              mat-raised-button
              color="primary"
              type="submit"
              [disabled]="!form.valid || isLoading()"
              class="start-btn"
            >
              @if (isLoading()) {
                <span class="spinner"></span>
                Starting...
              } @else {
                Start Research
              }
            </button>

            <div class="quick-topics">
              <p>Suggested topics:</p>
              <mat-chip-set>
                <mat-chip-option (click)="setQuickTopic('Impact of AI on Software Engineering')">
                  AI & Software Engineering
                </mat-chip-option>
                <mat-chip-option (click)="setQuickTopic('Quantum Computing Applications in 2026')">
                  Quantum Computing
                </mat-chip-option>
              </mat-chip-set>
            </div>

          </form>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .research-input-container {
      display: flex;
      justify-content: center;
      padding: 40px 24px;
    }

    .input-card {
      width: 100%;
      max-width: 700px;
      border-radius: 16px !important;
      box-shadow: 0 4px 24px rgba(99, 102, 241, 0.08) !important;
      transition: box-shadow 0.3s ease;
    }

    .input-card:hover {
      box-shadow: 0 8px 32px rgba(99, 102, 241, 0.12) !important;
    }

    /* Card header */
    .card-header { padding: 24px 24px 0; }
    .card-header h1 { margin: 0 0 8px; font-size: 1.75rem; font-weight: 800; color: #0f0f23 !important; }
    .card-header p { margin: 0; color: #4a4a6a !important; font-size: 0.95rem; line-height: 1.5; }

    /* Form fields */
    mat-card-content { padding-top: 16px; }
    .full-width { width: 100%; margin-bottom: 20px; }
    .half-width { width: 48%; margin-right: 4%; }
    .half-width:last-of-type { margin-right: 0; }

    /* Advanced options grid */
    .advanced-options { display: flex; justify-content: space-between; margin-bottom: 24px; gap: 8px; }

    /* Submit button */
    button[mat-raised-button] {
      border-radius: 10px !important;
      font-weight: 600;
      text-transform: none;
      letter-spacing: 0.5px;
      padding: 0 24px;
      height: 48px;
      transition: all 0.2s ease;
    }

    button[mat-raised-button]:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(99, 102, 241, 0.3);
    }

    /* Spinner in button */
    .spinner {
      display: inline-block;
      width: 18px;
      height: 18px;
      border: 2px solid rgba(255, 255, 255, 0.3);
      border-top-color: #fff;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
      margin-right: 8px;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    /* Quick topics */
    .quick-topics {
      margin-top: 24px;
      padding-top: 16px;
      border-top: 1px solid #f0f0f0;
    }
    .quick-topics p {
      font-size: 0.85rem;
      color: #1a1a2e !important;
      margin-bottom: 10px;
      font-weight: 600;
    }

    /* Dark mode */
    @media (prefers-color-scheme: dark) {
      .card-header h1 { color: #e0e0e0; }
      .quick-topics p { color: #9e9e9e; }
      .advanced-options { border-top-color: rgba(255, 255, 255, 0.1); }
    }

    /* Fade in animation */
    @keyframes fadeIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
  `]
})
export class ResearchInputComponent {

  private fb = inject(FormBuilder);
  private router = inject(Router);
  private researchService = inject(ResearchService);

  isLoading = signal(false);

  form = this.fb.group({
    topic: ['', Validators.required],
    maxIterations: [null as number | null],
    subTopicCount: [null as number | null]
  });

  onSubmit(): void {
    if (!this.form.valid || this.isLoading()) {
      return;
    }

    this.isLoading.set(true);

    const request = {
      topic: this.form.value.topic!,
      maxIterations: this.form.value.maxIterations ?? undefined,
      subTopicCount: this.form.value.subTopicCount ?? undefined
    };

    this.researchService.startResearch(request).subscribe({
      next: (session) => {
        this.router.navigate(['/research', session.id]);
      },
      error: (error) => {
        console.error('Failed to start research:', error);
        this.isLoading.set(false);
      }
    });
  }

  setQuickTopic(topic: string): void {
    this.form.patchValue({ topic });
  }
}
