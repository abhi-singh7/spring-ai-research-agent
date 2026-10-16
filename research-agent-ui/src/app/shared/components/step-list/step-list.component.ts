import { Component, input, ViewEncapsulation } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MarkdownModule } from 'ngx-markdown';

@Component({
  selector: 'step-list',
  standalone: true,
  imports: [MatCardModule, MatIconModule, MatProgressSpinnerModule, MarkdownModule],
  template: `
    <mat-card class="steps-card">
      <mat-card-header><mat-card-title>Research Steps</mat-card-title></mat-card-header>
      <mat-card-content>
        @if (steps().length > 0) {
          <ol class="step-list">
            @for (step of steps(); track step.stepNumber) {
              <li class="step-item" [class.active]="activeStepIndex() === step.stepNumber - 1">
                <span class="step-number">{{ step.stepNumber }}</span>

                <!-- Status: Material iconography, spinner for the live step -->
                @if (step.status === 'COMPLETED') {
                  <mat-icon class="step-completed">check_circle</mat-icon>
                }
                @if (step.status === 'IN_PROGRESS') {
                  <mat-progress-spinner class="step-running" mode="indeterminate" diameter="18" strokeWidth="2" />
                }
                @if (step.status === 'FAILED') {
                  <mat-icon class="step-failed">error</mat-icon>
                }
                @if (step.status === 'PENDING') {
                  <mat-icon class="step-pending">radio_button</mat-icon>
                }

                <!-- Step name -->
                <span class="step-name">{{ step.name }}</span>
              </li>

              <!-- Description / progress text -->
              @if (step.description) {
                <div class="step-description-wrapper">
                  <markdown class="step-description">
                    {{ step.description }}
                  </markdown>
                </div>
              }
            }
          </ol>
        } @else {
          <p class="empty-state">No steps yet</p>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .steps-card { margin-bottom: 16px; }
    .step-list { list-style: none; padding-left: 0; margin: 0; }
    .step-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      border-bottom: 1px solid var(--rp-outline);
    }
    .step-number {
      min-width: 32px;
      height: 32px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: bold;
      background: var(--rp-brand-08);
      color: var(--rp-on-surface);
    }
    .active .step-number {
      background: var(--rp-primary);
      color: var(--rp-on-primary);
    }
    mat-icon {
      width: 18px;
      height: 18px;
      font-size: 18px;
      line-height: 18px;
    }
    .step-completed { color: var(--rp-success); }
    .step-failed { color: var(--rp-error); }
    .step-pending { color: var(--rp-outline); }
    .step-running { margin: 3px; }
    .step-name { font-weight: 500; }
    .step-description {
      margin-top: 4px;
      padding-left: 40px;
      color: var(--rp-on-surface-variant);
      white-space: pre-wrap;
    }
    /* Constrain code blocks from overflowing the step description area */
    :host ::ng-deep markdown,
    :host ::ng-deep .markdown-body { max-width: 100% !important; overflow-x: hidden !important; }
    :host ::ng-deep pre,
    :host ::ng-deep code { white-space: pre-wrap !important; word-wrap: break-word !important; max-width: 100% !important; overflow-x: hidden !important; }
    .empty-state { text-align: center; color: var(--rp-on-surface-variant); padding: 20px; }
  `]
})
export class StepListComponent {
  steps = input.required<any[]>();
  activeStepIndex = input(-1);
}
