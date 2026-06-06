import { Component, inject, signal } from '@angular/core';
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
  template: `
    <div class="research-input-container">
      <mat-card>
        <mat-card-header>
          <mat-card-title>New Research</mat-card-title>
        </mat-card-header>

        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="onSubmit()">

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Research Topic</mat-label>
              <input
                matInput
                formControlName="topic"
                placeholder="Enter your research topic..."
              />

              @if (form.get('topic')?.hasError('required')) {
                <mat-error>Topic is required</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Maximum Iterations (optional)</mat-label>
              <input
                matInput
                type="number"
                formControlName="maxIterations"
                min="1"
                max="50"
              />
              <mat-hint>
                Sets the maximum number of research iterations
              </mat-hint>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Sub-topics (optional)</mat-label>
              <input
                matInput
                type="number"
                formControlName="subTopicCount"
                min="1"
                max="20"
              />
              <mat-hint>
                Number of sub-topics to research in parallel
              </mat-hint>
            </mat-form-field>

            <button
              mat-raised-button
              color="primary"
              type="submit"
              [disabled]="!form.valid || isLoading()"
            >
              Start Research
            </button>

            <div class="quick-topics">
              <p>Suggested topics:</p>

              <mat-chip-set>
                <mat-chip-option
                  (click)="setQuickTopic('Impact of AI on Software Engineering')"
                >
                  AI & Software Engineering
                </mat-chip-option>

                <mat-chip-option
                  (click)="setQuickTopic('Quantum Computing Applications in 2026')"
                >
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
      padding: 40px;
    }

    mat-card {
      width: 100%;
      max-width: 700px;
    }

    .full-width {
      width: 100%;
      margin-bottom: 16px;
    }

    button[mat-raised-button] {
      margin-right: 8px;
    }

    .quick-topics {
      margin-top: 24px;
    }

    .quick-topics p {
      font-size: 0.9rem;
      color: #666;
      margin-bottom: 8px;
    }
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