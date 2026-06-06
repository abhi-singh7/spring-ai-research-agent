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
      <h2>Research History</h2>

      <!-- Search bar -->
      <mat-form-field appearance="outline" class="full-width search-bar">
        <mat-label>Search topics...</mat-label>
        <input matInput placeholder="Search by topic..." (keyup)="onSearch($event)" />
      </mat-form-field>

      <!-- Session list -->
      @if (sessions().length > 0) {
        <div class="session-list">
          @for (item of sessions(); track item.id) {
            <mat-card class="history-item" [routerLink]="['/research/history', item.id]">
              <mat-card-content>
                <span class="topic">{{ item.topic }}</span>
                <span class="status-chip" [class.pending]="item.status === 'PENDING'"
                      [class.processing]="item.status === 'PROCESSING'"
                      [class.completed]="item.status === 'COMPLETED'"
                      [class.failed]="item.status === 'FAILED'"
                      [class.cancelled]="item.status === 'CANCELLED'">
                  {{ item.status }}
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
          }
        </div>
      } @else {
        <p class="empty-state">No research history found. Start a new research to see it here.</p>
      }

      <!-- Pagination -->
      @if (totalPages() > 1) {
        <div class="pagination">
          <button mat-button [disabled]="currentPage() <= 0" (click)="loadPage(currentPage() - 1)">← Previous</button>
          <span class="page-info">{{ currentPage() + 1 }} / {{ totalPages() }}</span>
          <button mat-button [disabled]="currentPage() >= totalPages() - 1" (click)="loadPage(currentPage() + 1)">Next →</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .research-history-container { padding: 24px; max-width: 1000px; margin: 0 auto; }
    .search-bar { width: 100%; margin-bottom: 24px; }
    .session-list { display: flex; flex-direction: column; gap: 8px; }
    .history-item { cursor: pointer; transition: box-shadow 0.2s; }
    .history-item:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.15); }
    .topic { font-weight: 500; }
    .status-chip { padding: 2px 8px; border-radius: 12px; font-size: 0.75rem; margin-left: auto; }

    /* Light mode status chip colors */
    .pending { background-color: #fff3e0; color: #ef6c00; }
    .processing { background-color: #e3f2fd; color: #1565c0; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .cancelled { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    /* Dark mode status chip colors */
    @media (prefers-color-scheme: dark) {
      .pending { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; }
      .processing { background-color: rgba(66, 165, 245, 0.2); color: #42a5f5; }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; }
      .cancelled { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

    .empty-state { text-align: center; color: #999; padding: 40px; }
    .pagination { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 24px; }
    .page-info { font-size: 0.85rem; color: #666; }

    @media (prefers-color-scheme: dark) {
      .empty-state { color: #9e9e9e; }
      .page-info { color: #bdbdbd; }
    }
  `]
})
export class ResearchHistoryComponent implements OnInit {

  constructor() {
  console.log('ResearchHistoryComponent constructor');
}

  private historyService = inject(ResearchHistoryService);

  sessions = this.historyService.sessions;
  currentPage = this.historyService.currentPage;
  totalPages = this.historyService.totalPages;
  totalElements = this.historyService.totalElements;

  ngOnInit(): void {
    console.log('[ResearchHistoryComponent] Initializing and loading history...');
    this.historyService.loadHistory();
  }

  getStatusClass(status: string): string {
    const classes: Record<string, string> = {
      PENDING: 'pending', PROCESSING: 'processing', COMPLETED: 'completed', FAILED: 'failed', CANCELLED: 'cancelled'
    };
    return classes[status] || '';
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
