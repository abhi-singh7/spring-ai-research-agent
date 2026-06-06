import { Component, input, ViewEncapsulation } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MarkdownModule } from 'ngx-markdown';

@Component({
  selector: 'step-list',
  standalone: true,
  imports: [MatCardModule, MarkdownModule],
  template: `
    <mat-card class="steps-card">
      <mat-card-header><mat-card-title>Research Steps</mat-card-title></mat-card-header>
      <mat-card-content>
        @if (steps().length > 0) {
          <ol class="step-list">
            @for (step of steps(); track step.stepNumber) {
              <li class="step-item" [class.active]="activeStepIndex() === step.stepNumber - 1">
                <span class="step-number">{{ step.stepNumber }}</span>

                <!-- Status icon -->
                @if (step.status === 'COMPLETED') {
                  <span class="step-icon completed">&#10003;</span>
                }
                @if (step.status === 'IN_PROGRESS') {
                  <span class="step-icon running">&#8635;</span>
                }
                @if (step.status === 'FAILED') {
                  <span class="step-icon failed">&#10007;</span>
                }
                @if (step.status === 'PENDING') {
                  <span class="step-icon pending">&#9675;</span>
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
    .step-item { display: flex; align-items: center; gap: 8px; padding: 12px; border-bottom: 1px solid #e0e0e0; }
    .step-number { min-width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: bold; background: #f0f0f0; }
    .active .step-number { background: var(--mat-primary-color); color: white; }
    .step-icon { width: 24px; text-align: center; }
    .completed { color: green; }
    .running { animation: spin 1s linear infinite; }
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
    .failed { color: red; }
    .pending { opacity: 0.5; }
    .step-name { font-weight: 500; }
    .step-description { margin-top: 4px; padding-left: 40px; color: #616161; white-space: pre-wrap; }
    /* Constrain code blocks from overflowing the step description area */
    :host ::ng-deep markdown,
    :host ::ng-deep .markdown-body { max-width: 100% !important; overflow-x: hidden !important; }
    :host ::ng-deep pre,
    :host ::ng-deep code { white-space: pre-wrap !important; word-wrap: break-word !important; max-width: 100% !important; overflow-x: hidden !important; }
    .empty-state { text-align: center; color: #999; padding: 20px; }
  `]
})
export class StepListComponent {
  steps = input.required<any[]>();
  activeStepIndex = input(-1);
}
