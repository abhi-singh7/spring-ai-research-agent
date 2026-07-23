import { Component, inject, OnInit } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ResearchHistoryService } from '../../core/services/research-history.service';

@Component({
  selector: 'app-research-history',
  standalone: true,
  imports: [MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, RouterLink],
  template: `
    <div class="research-history-container">
      <!-- Header -->
      <div class="page-header" [@fadeIn]>
        <h2>Research History</h2>
        <span class="session-count">{{ totalElements() }} session{{ totalElements() === 1 ? '' : 's' }}</span>
      </div>

      <!-- Search bar -->
      <mat-form-field appearance="outline" class="full-width search-bar">
        <mat-label>Search topics...</mat-label>
        <input matInput placeholder="Filter by topic..." (keyup)="onSearch($event)" />
      </mat-form-field>

      <!-- Session list -->
      @if (sessions().length > 0) {
        <div class="session-list">
          @for (item of sessions(); track item.id) {
            <a [routerLink]="['/research/history', item.id]" class="history-item" [@fadeIn]>
              <mat-card class="item-card">
                <mat-card-content>
                  <span class="topic">{{ item.topic }}</span>
                  <span class="status-chip" [class.pending]="item.status === 'PENDING'"
                        [class.processing]="item.status === 'PROCESSING'"
                        [class.completed]="item.status === 'COMPLETED'"
                        [class.failed]="item.status === 'FAILED'"
                        [class.cancelled]="item.status === 'CANCELLED'">
                    {{ getStatusLabel(item.status) }}
                  </span>
                </mat-card-content>
                <mat-card-actions class="history-item-actions" align="end">
                  @if (item.createdAt) {
                    <span class="date-info">{{ formatDateTime(item.createdAt) }}</span>
                  }
                  @if (item.completedAt && item.status === 'COMPLETED') {
                    <span class="completed-info">Completed: {{ formatDateTime(item.completedAt) }}</span>
                  }
                </mat-card-actions>
              </mat-card>
            </a>
          }
        </div>
      } @else {
        <div class="empty-state" [@fadeIn]>
          <p>No research history found.</p>
          <a routerLink="/" mat-raised-button color="primary">Start New Research</a>
        </div>
      }

      <!-- Pagination -->
      @if (totalPages() > 1) {
        <div class="pagination" [@fadeIn]>
          <button mat-stroked-button [disabled]="currentPage() <= 0" (click)="loadPage(currentPage() - 1)">Previous</button>
          <span class="page-info">Page {{ currentPage() + 1 }} of {{ totalPages() }}</span>
          <button mat-stroked-button [disabled]="currentPage() >= totalPages() - 1" (click)="loadPage(currentPage() + 1)">Next</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .research-history-container { padding: 24px; max-width: 1000px; margin: 0 auto; }

    /* Page header */
    .page-header { display: flex; align-items: baseline; gap: 12px; margin-bottom: 8px; }
    .page-header h2 { margin: 0; font-size: 1.75rem; font-weight: 700; color: #0f0f23 !important; }
    .session-count { font-size: 0.9rem; color: #1a1a2e !important; font-weight: 400; }

    /* Search bar */
    .search-bar { width: 100%; margin-bottom: 24px; }

    /* Session list */
    .session-list { display: flex; flex-direction: column; gap: 10px; }

    /* History item card */
    a.history-item { text-decoration: none; color: inherit; }
    .item-card { border-radius: 12px !important; overflow: hidden; transition: all 0.2s ease; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.05); cursor: pointer; }
    .item-card:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(99, 102, 241, 0.12); }

    mat-card-content { display: flex; align-items: center; gap: 16px; padding: 14px 18px !important; }
    .topic { font-weight: 600; font-size: 0.95rem; color: #0f0f23 !important; flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

    /* Status chip */
    .status-chip { padding: 4px 12px; border-radius: 20px; font-size: 0.75rem; font-weight: 500; letter-spacing: 0.3px; flex-shrink: 0; transition: all 0.3s ease; }
    .pending { background-color: #fff3e0; color: #ef6c00; }
    .processing { background-color: #e3f2fd; color: #1565c0; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .cancelled { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    /* Dark mode status chip colors */
    @media (prefers-color-scheme: dark) {
      .page-header h2 { color: #e0e0e0; }
      .topic { color: #e0e0e0; }
      .pending { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; }
      .processing { background-color: rgba(66, 165, 245, 0.2); color: #42a5f5; }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; }
      .cancelled { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

    /* Card actions */
    .history-item-actions { padding-right: 16px !important; display: flex; align-items: center; gap: 16px; }
    .date-info, .completed-info { font-size: 0.8rem; color: #1a1a2e !important; white-space: nowrap; }

    /* Empty state */
    .empty-state { text-align: center; padding: 48px 24px; color: #1a1a2e !important; }
    .empty-state p { margin-bottom: 16px; font-size: 1rem; }

    /* Pagination */
    .pagination { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 24px; padding: 16px 0; }
    .page-info { font-size: 0.85rem; color: #1a1a2e !important; font-weight: 500; min-width: 120px; text-align: center; }

    /* Dark mode pagination */
    @media (prefers-color-scheme: dark) {
      .empty-state p { color: #9e9e9e; }
      .page-info { color: #bdbdbd; }
    }

    /* Fade in animation */
    @keyframes fadeIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
  `]
})
export class ResearchHistoryComponent implements OnInit {

  private historyService = inject(ResearchHistoryService);

  sessions = this.historyService.sessions;
  currentPage = this.historyService.currentPage;
  totalPages = this.historyService.totalPages;
  totalElements = this.historyService.totalElements;

  ngOnInit(): void {
    this.historyService.loadHistory();
  }

  getStatusLabel(status: string): string {
    const labels: Record<string, string> = {
      PENDING: 'Pending', PROCESSING: 'Processing...', COMPLETED: 'Completed', FAILED: 'Failed', CANCELLED: 'Cancelled'
    };
    return labels[status] || status;
  }

  formatDateTime(dateStr: string): string {
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return dateStr;
    return date.toLocaleString('en-US', {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit'
    });
  }

  onSearch(event: Event): void {
    const input = (event.target as HTMLInputElement).value.trim();
    this.historyService.loadHistory(this.currentPage(), input);
  }

  loadPage(page: number): void {
    const search = this.historyService.searchTerm?.trim() || '';
    this.historyService.loadHistory(page, search);
  }
}
