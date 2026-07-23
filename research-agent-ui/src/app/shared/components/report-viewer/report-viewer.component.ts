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
      </mat-card-header>
      <mat-card-content class="report-content">
        <!-- Rendered markdown (block-level for proper heading styling) -->
        <markdown [data]="content()"></markdown>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    :host ::ng-deep .markdown-body h1,
    :host ::ng-deep markdown h1 { color: #0f0f23; font-weight: 700; margin-bottom: 16px; }

    :host ::ng-deep .markdown-body h2,
    :host ::ng-deep markdown h2 { color: #1a1a2e; font-weight: 600; }

    :host ::ng-deep .markdown-body h3,
    :host ::ng-deep markdown h3 { color: #1a1a2e; font-weight: 600; }

    .report-content { padding: 16px; line-height: 1.7; max-width: 900px; margin: 0 auto; }
  `]
})
export class ReportViewerComponent {
  content = input.required<string>();
  title = input('Research Report');
}
