import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ReportViewerComponent } from '../../shared/components/report-viewer/report-viewer.component';
import { ResearchService } from '../../core/services/research.service';
import { ResearchSession } from '../../core/models/research.model';

/**
 * Chrome-less full-page document view for a historical research session.
 * Thin wrapper over report-viewer: all reading logic (TOC, scroll-spy, font
 * controls, progress) lives in the shared component.
 */
@Component({
  selector: 'app-report-document',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, RouterLink, DatePipe, ReportViewerComponent],
  template: `
    <div class="doc-page">
      <header class="doc-topbar">
        <a routerLink="/research/history" class="back-link">
          <mat-icon>arrow_back</mat-icon> Back to History
        </a>
        <button mat-icon-button (click)="print()" title="Print / Save as PDF" aria-label="Print report">
          <mat-icon>print</mat-icon>
        </button>
      </header>

      @if (isLoading()) {
        <p class="doc-state">Loading session...</p>
      } @else if (hasError()) {
        <div class="doc-state">
          <p>Failed to load session. Please try again.</p>
          <button mat-raised-button color="primary" (click)="retry()">Retry</button>
        </div>
      } @else if (session()?.finalReport) {
        <article class="doc-article">
          <header class="doc-header">
            <h1>{{ session()!.topic }}</h1>
            <p class="doc-meta">
              @if (session()!.completedAt) {
                Completed {{ session()!.completedAt | date:'MMM d, yyyy · h:mm a' }}
              } @else {
                Started {{ session()!.createdAt | date:'MMM d, yyyy · h:mm a' }}
              }
            </p>
          </header>
          <report-viewer [content]="session()!.finalReport!" [title]="session()!.topic" />
        </article>
      } @else {
        <p class="doc-state">This session has no report to display.</p>
      }
    </div>
  `,
  styles: [`
    .doc-page { min-height: 100vh; background: #f7f8fb; padding: 0 24px 64px; }

    .doc-topbar {
      position: sticky; top: 0; z-index: 10;
      display: flex; align-items: center; justify-content: space-between;
      padding: 10px 0; margin-bottom: 8px;
      background: rgba(247, 248, 251, 0.9);
      backdrop-filter: blur(6px);
    }
    .back-link {
      display: inline-flex; align-items: center; gap: 6px;
      color: #1a1a2e; text-decoration: none; font-weight: 500;
      padding: 8px 14px; border-radius: 8px;
      transition: all 0.2s ease;
    }
    .back-link:hover { background: rgba(99, 102, 241, 0.08); color: #4f46e5; }
    .back-link mat-icon { font-size: 20px; width: 20px; height: 20px; line-height: 20px; }

    .doc-article { max-width: 1000px; margin: 0 auto; }
    .doc-header h1 { font-size: 2rem; font-weight: 700; color: #0f0f23; line-height: 1.25; margin: 24px 0 8px; }
    .doc-meta { color: #8a8f98; font-size: 0.9rem; margin: 0 0 24px; }

    /* Headings must clear the sticky top bar when scroll-targeted */
    .doc-article .report-doc h1,
    .doc-article .report-doc h2,
    .doc-article .report-doc h3 { scroll-margin-top: 76px; }

    .doc-state { text-align: center; padding: 60px 0; color: #999; font-size: 1rem; }
    .doc-state p { margin-bottom: 16px; }

    /* Dark mode */
    @media (prefers-color-scheme: dark) {
      .doc-page { background: #14161b; }
      .doc-topbar { background: rgba(20, 22, 27, 0.9); }
      .back-link { color: #e5e7eb; }
      .back-link:hover { background: rgba(129, 140, 248, 0.12); color: #a5b4fc; }
      .doc-header h1 { color: #f0f2f5; }
      .doc-meta { color: #8b919c; }
      .doc-state { color: #9e9e9e; }
    }
  `]
})
export class ReportDocumentComponent {
  private route = inject(ActivatedRoute);
  private researchService = inject(ResearchService);

  session = signal<ResearchSession | null>(null);
  isLoading = signal(true);
  hasError = signal(false);

  private sessionId: string | null = null;

  ngOnInit(): void {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId');
    if (this.sessionId) this.loadSession(this.sessionId);
  }

  print(): void { window.print(); }

  retry(): void {
    if (this.sessionId) this.loadSession(this.sessionId);
  }

  loadSession(sessionId: string): void {
    this.isLoading.set(true);
    this.hasError.set(false);
    this.researchService.getHistoricalSession(sessionId).subscribe({
      next: (session) => {
        this.session.set(session);
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('[ReportDocumentComponent] Failed to load session:', error);
        this.hasError.set(true);
        this.isLoading.set(false);
      }
    });
  }
}
