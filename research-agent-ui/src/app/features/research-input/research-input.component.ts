import { Component, inject, signal } from '@angular/core';
import { trigger, transition, style, animate } from '@angular/animations';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
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
    MatProgressSpinnerModule,
    MatExpansionModule,
    MatDividerModule,
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

            <mat-expansion-panel class="advanced-panel">
              <mat-expansion-panel-header>
                <mat-panel-title>Advanced options</mat-panel-title>
                <mat-panel-description>Optional — depth and breadth of the research</mat-panel-description>
              </mat-expansion-panel-header>

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
            </mat-expansion-panel>

            <button
              mat-raised-button
              type="submit"
              [disabled]="!form.valid || isLoading()"
              class="mat-primary start-btn"
            >
              @if (isLoading()) {
                <mat-progress-spinner class="btn-spinner" mode="indeterminate" diameter="18" strokeWidth="2" />
                Starting...
              } @else {
                Start Research
              }
            </button>

            <mat-divider></mat-divider>

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
      /* Material card API — shape + elevation tokens (scheme-aware shadows). */
      --mdc-elevated-card-container-shape: 16px;
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-2);
    }

    .input-card:hover {
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-5);
    }

    /* Card header */
    .card-header { padding: 24px 24px 0; }
    .card-header h1 { margin: 0 0 8px; font-size: 1.75rem; font-weight: 800; color: var(--rp-on-surface) !important; }
    .card-header p { margin: 0; color: var(--rp-on-surface-variant) !important; font-size: 0.95rem; line-height: 1.5; }

    /* Form fields */
    mat-card-content { padding-top: 16px; }
    .full-width { width: 100%; margin-bottom: 20px; }
    .half-width { width: 48%; margin-right: 4%; }
    .half-width:last-of-type { margin-right: 0; }

    /* Advanced options (Material expansion panel) */
    .advanced-panel {
      margin: 20px 0;
      /* Sit flush inside the input card — the card already provides the surface. */
      --mat-expansion-container-background-color: transparent;
      --mat-expansion-container-shape: 12px;
    }
    .advanced-panel mat-expansion-panel-header {
      --mdc-filled-height: 48px;
      --mdc-filled-shape-size: 12px;
    }
    .advanced-options { display: flex; justify-content: space-between; margin-bottom: 16px; gap: 8px; }

    /* Submit button */
    button[mat-raised-button] {
      /* Material protected-button API: hover elevation is a token, not a hack. */
      --mdc-protected-button-container-shape: 10px;
      --mdc-protected-button-container-elevation-shadow: var(--mat-app-elevation-shadow-level-1);
      --mdc-protected-button-hover-container-elevation-shadow: var(--mat-app-elevation-shadow-level-4);
      --mdc-protected-button-pressed-container-elevation-shadow: var(--mat-app-elevation-shadow-level-0);
      font-weight: 600;
      text-transform: none;
      letter-spacing: 0.5px;
      padding: 0 24px;
      height: 48px;
    }

    /* Spinner in button */
    .btn-spinner {
      display: inline-block;
      vertical-align: middle;
      margin-right: 8px;
    }

    /* Quick topics (separated by a Material divider) */
    .quick-topics {
      margin-top: 16px;
      padding-top: 16px;
    }
    .quick-topics p {
      font-size: 0.85rem;
      color: var(--rp-on-surface) !important;
      margin-bottom: 10px;
      font-weight: 600;
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
