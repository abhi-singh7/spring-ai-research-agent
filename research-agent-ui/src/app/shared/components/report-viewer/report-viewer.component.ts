import { Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MarkdownModule } from 'ngx-markdown';

@Component({
  selector: 'report-viewer',
  standalone: true,
  imports: [MatCardModule, MarkdownModule],
  template: `
    <mat-card class="report-card">
      <mat-card-header>
        <mat-card-title>Research Report</mat-card-title>
        @if (title()) {
          <mat-card-subtitle>{{ title() }}</mat-card-subtitle>
        }
      </mat-card-header>
      <mat-card-content class="report-content">
        <!-- Rendered markdown (block-level for proper heading styling) -->
        <markdown [data]="content()"></markdown>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .report-content { padding: 16px; line-height: 1.7; max-width: 900px; margin: 0 auto; }
  `]
})
export class ReportViewerComponent {
  content = input.required<string>();
  title = input('Research Report');
}
