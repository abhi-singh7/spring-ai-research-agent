import { Component, ViewEncapsulation, inject, OnInit } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ResearchHistoryService } from '../../core/services/research-history.service';
import { MatDialog, MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBarModule, MatSnackBar } from '@angular/material/snack-bar';
import { MatCheckboxModule } from '@angular/material/checkbox';

/**
 * Reusable delete confirmation dialog.
 */
@Component({
  selector: 'app-delete-confirmation',
  standalone: true,
  encapsulation: ViewEncapsulation.None,
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>Delete Confirmation</h2>
    <mat-dialog-content class="delete-confirm-content">
      <p>{{ data.message }}</p>
      @if (data && data.topics && data.topics.length > 0) {
        <div class="topic-list">
          @for (topic of data!.topics!; track topic) {
            <span class="topic-item">{{ topic }}</span>
          }
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="delete-confirm-actions">
      <button mat-stroked-button color="primary" (click)="dialogRef.close(false)">Cancel</button>
      <button mat-raised-button color="warn" (click)="dialogRef.close(true)">Delete Permanently</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .delete-confirm-content { min-width: 300px; max-width: 500px; }
    .topic-list { margin-top: 12px; padding-left: 8px; border-left: 2px solid #ccc; }
    @media (prefers-color-scheme: dark) { .topic-list { border-left-color: #555; } }
    .topic-item { display: block !important; font-size: 0.85rem !important; font-weight: 600 !important; padding: 2px 0 !important; white-space: nowrap !important; overflow: hidden !important; text-overflow: ellipsis !important; max-width: 400px !important; }
    /* Use Material M3's dialog supporting-text-color variable — adapts automatically to whichever theme the dialog is using (light or dark) */
    .delete-confirm-content .topic-list .topic-item,
    .mdc-dialog__content .delete-confirm-content .topic-item {
      color: var(--mdc-dialog-supporting-text-color, #1a1a2e) !important;
      padding-left: 6px !important;
    }
    .mat-dialog-container { border-radius: 12px !important; padding: 24px !important; overflow: visible !important; }
  `]
})
class DeleteConfirmationDialogComponent {
  readonly data = inject<DeleteConfirmData>(MAT_DIALOG_DATA);
  readonly dialogRef = inject(MatDialogRef<DeleteConfirmationDialogComponent>);
}

interface DeleteConfirmData {
  message: string;
  topics?: string[];
}

/**
 * Main research history component.
 * Features: pagination, search, single delete (MatDialog), bulk multi-select delete (Approach A).
 */
@Component({
  selector: 'app-research-history',
  standalone: true,
  imports: [
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    RouterLink,
    MatDialogModule,
    MatSnackBarModule,
    MatCheckboxModule,
  ],
  template: `
    <div class="research-history-container">

      <!-- Page header -->
      <div class="page-header fade-in">
        <h2>Research History</h2>
        @if (!hasSelection()) {
          <span class="session-count">{{ totalElements() }} session{{ totalElements() === 1 ? '' : 's' }}</span>
        }
      </div>

      <!-- Search bar -->
      <mat-form-field appearance="outline" class="full-width search-bar fade-in">
        <mat-label>Search topics...</mat-label>
        <input matInput placeholder="Filter by topic..." (keyup)="onSearch($event)" />
      </mat-form-field>

      <!-- Action toolbar — always visible when there are sessions -->
      @if (sessions().length > 0) {
        <div class="action-toolbar fade-in">
           @if (!inSelectionMode && !hasActualSelections()) {
             <button mat-icon-button color="primary" (click)="enterSelectionMode()" [attr.aria-label]="'Delete multiple items'">
               <mat-icon>delete_sweep</mat-icon>
             </button>
           }

          @if (inSelectionMode && hasActualSelections()) {
            <span class="selection-count">{{ selectionCount() }} selected</span>
            <button mat-stroked-button color="primary" class="select-all-btn" (click)="toggleSelectAll()">
              {{ isCurrentPageSelected() ? 'Deselect All' : 'Select All' }}
            </button>
            <button mat-stroked-button color="warn" [disabled]="!hasActualSelections()"
                    class="delete-btn" (click)="onBulkDelete()">
              Delete Selected
            </button>
          }

          <!-- Exit selection mode — always shown when in selection mode so user can cancel -->
          @if (inSelectionMode) {
            <span style="flex-shrink: 0;">&nbsp;</span>
            <button mat-stroked-button color="primary" class="cancel-selection-btn" (click)="exitSelectionMode()">Cancel</button>
          }
        </div>
      }

      <!-- ---- Normal view: single cards with delete button per item (no selection mode, no selections yet) ---- -->
      @if (!inSelectionMode && !hasActualSelections() && sessions().length > 0) {
        <div class="session-list">
          @for (item of sessions(); track item.id) {
            <!-- Wrapper: card fills space, delete button on right edge -->
            <div class="history-item-wrapper fade-in" style="cursor: pointer;">

              <mat-card class="item-card" [routerLink]="['/research/history', item.id]">
                <mat-card-content class="card-body">
                  <span class="topic">{{ item.topic }}</span>
                  <div class="card-right-side">
                    <span class="status-chip"
                          [class.pending]="item.status === 'PENDING'"
                          [class.processing]="item.status === 'PROCESSING'"
                          [class.completed]="item.status === 'COMPLETED'"
                          [class.failed]="item.status === 'FAILED'"
                          [class.cancelled]="item.status === 'CANCELLED'">
                      {{ getStatusLabel(item.status) }}
                    </span>

                    @if (item.createdAt || item.completedAt) {
                      <div class="card-date-row">
                        @if (item.createdAt) {
                          <span class="date-info">{{ formatDateTime(item.createdAt) }}</span>
                        }
                        @if (item.completedAt && item.status === 'COMPLETED') {
                          <span class="completed-info">Completed: {{ formatDateTime(item.completedAt) }}</span>
                        }
                      </div>
                    }
                  </div>
                </mat-card-content>
              </mat-card>

              <!-- Delete button — outside the navigable card area so [routerLink] doesn't intercept its clicks -->
              <button mat-icon-button color="warn" class="delete-btn-outside normal-view-delete"
                      (click)="onDeleteSingle(item, $event)"
                      [attr.aria-label]="'Delete research session: ' + item.topic">
                <mat-icon>delete_forever</mat-icon>
              </button>

            </div>
          }
        </div>
      }

      <!-- ---- Selection mode view: individual checkboxes on each card with delete button per item ---- -->
      @if ((inSelectionMode || hasActualSelections()) && sessions().length > 0) {
        <div class="session-list">

          @for (item of sessions(); track item.id) {
            <!-- Row: checkbox — card content — delete on right -->
            <div class="history-item-wrapper selection-row fade-in"
                 style="cursor: pointer;"
                 [class.selected-item]="isSelected(item.id)">

              <!-- Checkbox --><mat-checkbox class="selection-mode-checkbox"
                            (change)="toggleSelect($event, item)"
                            [checked]="isSelected(item.id)"></mat-checkbox>

            <div class="selection-card-delete-area">
              <mat-card class="item-card">
                <mat-card-content class="card-body">
                  <span class="topic">{{ item.topic }}</span>
                  <div class="card-right-side">
                    <span class="status-chip"
                          [class.pending]="item.status === 'PENDING'"
                          [class.processing]="item.status === 'PROCESSING'"
                          [class.completed]="item.status === 'COMPLETED'"
                          [class.failed]="item.status === 'FAILED'"
                          [class.cancelled]="item.status === 'CANCELLED'">
                        {{ getStatusLabel(item.status) }}
                      </span>

                    @if (item.createdAt || item.completedAt) {
                      <div class="card-date-row">
                        @if (item.createdAt) {
                          <span class="date-info">{{ formatDateTime(item.createdAt) }}</span>
                        }
                        @if (item.completedAt && item.status === 'COMPLETED') {
                          <span class="completed-info">Completed: {{ formatDateTime(item.completedAt) }}</span>
                        }
                      </div>
                    }
                  </div>
                </mat-card-content>
              </mat-card>

            <!-- Delete button on the right --></div>
              <button mat-icon-button color="warn" class="delete-btn-outside"
                      (click)="onDeleteSingle(item, $event)"
                      [attr.aria-label]="'Delete research session: ' + item.topic">
                <mat-icon>delete_forever</mat-icon>
              </button>

            </div>
          }
        </div>
      }

      <!-- Empty state -->
      @if (sessions().length === 0 && !hasSelection()) {
        <div class="empty-state fade-in">
          <p>No research history found.</p>
          <a routerLink="/" mat-raised-button color="primary">Start New Research</a>
        </div>
      }

      <!-- Pagination -->
      @if (!hasSelection() && totalPages() > 1) {
        <div class="pagination fade-in">
          <button mat-stroked-button [disabled]="currentPage() <= 0" (click)="loadPage(currentPage() - 1)">Previous</button>
          <span class="page-info">Page {{ currentPage() + 1 }} of {{ totalPages() }}</span>
          <button mat-stroked-button [disabled]="currentPage() >= totalPages() - 1" (click)="loadPage(currentPage() + 1)">Next</button>
        </div>
      }

    </div>
  `,
  styles: [`
    .research-history-container { padding: 24px; max-width: 1000px; margin: 0 auto; }

    /* ---- Page header ---- */
    .page-header { display: flex; align-items: baseline; gap: 12px; margin-bottom: 8px; }
    .page-header h2 { margin: 0; font-size: 1.75rem; font-weight: 700; color: #0f0f23 !important; }
    .session-count { font-size: 0.9rem; color: #1a1a2e !important; font-weight: 400; }

    /* ---- Search bar ---- */
    .search-bar { width: 100%; margin-bottom: 24px; }

    /* ---- Action toolbar ---- */
    .action-toolbar {
      display: flex; align-items: center; gap: 16px; padding: 12px 18px;
      background-color: #f5f5f5; border-radius: 8px; margin-bottom: 16px; min-height: 44px;
    }
    .selection-count { font-size: 0.95rem; font-weight: 500; color: #1a1a2e !important; flex-shrink: 0; }
    @media (prefers-color-scheme: dark) { .selection-count { color: #eee; } }
    .delete-btn { padding: 0 24px; min-width: auto; font-weight: 500; letter-spacing: 0.3px; transition: all 0.2s ease; }
    .cancel-selection-btn {
      margin-left: 8px !important;
      color: var(--md-sys-color-primary, #6750a0) !important;
    }

    /* ---- Session list ---- */
    .session-list { display: flex; flex-direction: column; gap: 10px; }

    /* ---- History item wrapper (card + delete button in a row) ---- */
    .history-item-wrapper {
      display: flex; align-items: center; gap: 8px; width: 100%;
    }
    .history-item-wrapper mat-card {
      flex: 1 1 auto; min-width: 0;
    }
    .item-card { border-radius: 12px !important; overflow: hidden; transition: all 0.2s ease; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.05); cursor: pointer; position: relative; }
    .item-card:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(99, 102, 241, 0.12); }

    /* ---- Selection view row layout (checkbox + topic left-aligned, status chip + dates right-aligned) ---- */
    .delete-btn-outside {
      flex-shrink: 0;
      display: inline-flex !important;
      align-items: center !important;
      justify-content: center !important;
      width: 48px !important;
      height: 48px !important;
      border-radius: 50% !important;
      transition: all 0.2s ease !important;
    }
    .delete-btn-outside:hover {
      background-color: rgba(239, 68, 68, 0.1) !important;
      transform: scale(1.05) !important;
    }

    /* ---- Normal view card ---- */
    a.history-item { text-decoration: none; color: inherit; }

    /* ---- Selected card styling (normal view) ---- */
    .item-card.selected { border-left: 3px solid #6750a0 !important; background-color: rgba(103, 80, 160, 0.05) !important; }
    @media (prefers-color-scheme: dark) { .item-card.selected { background-color: rgba(103, 80, 160, 0.1) !important; } }

    /* ---- Selected row styling (selection mode — no card involved) ---- */
    .history-item-wrapper.selected { background-color: rgba(103, 80, 160, 0.05) !important; border-radius: 8px; }
    @media (prefers-color-scheme: dark) { .history-item-wrapper.selected { background-color: rgba(103, 80, 160, 0.1) !important; } }

    /* ---- Card body (topic left, status+dates right) ---- */
    .card-body { display: flex !important; align-items: center; justify-content: space-between; gap: 16px; min-height: 0; padding-right: 4px; }
    .card-right-side { display: inline-flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; max-width: 280px; }


    /* ---- Topic text ---- */
    .topic { font-weight: 600; font-size: 0.95rem; color: #0f0f23 !important; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }

    /* ---- Selection row (checkbox + topic) ---- */
    .selection-row { display: flex; align-items: center; gap: 12px; padding: 14px 18px 0 14px; }

    /* ---- Status chip ---- */
    .status-chip { padding: 4px 12px; border-radius: 20px; font-size: 0.75rem; font-weight: 500; letter-spacing: 0.3px; flex-shrink: 0; transition: all 0.3s ease; }
    .pending { background-color: #fff3e0; color: #ef6c00; }
    .processing { background-color: #e3f2fd; color: #1565c0; }
    .completed { background-color: #e8f5e9; color: #2e7d32; }
    .failed { background-color: #ffebee; color: #c62828; }
    .cancelled { background-color: #fafafa; color: #424242; border: 1px solid #bdbdbd; }

    @media (prefers-color-scheme: dark) {
      .page-header h2 { color: #e0e0e0; }
      .topic { color: #e0e0e0; }
      .pending { background-color: rgba(255, 183, 77, 0.2); color: #ffb74d; }
      .processing { background-color: rgba(66, 165, 245, 0.2); color: #42a5f5; }
      .completed { background-color: rgba(102, 187, 106, 0.2); color: #66bb6a; }
      .failed { background-color: rgba(239, 83, 80, 0.2); color: #ef5350; }
      .cancelled { background-color: rgba(158, 158, 158, 0.2); color: #bdbdbd; border: 1px solid #616161; }
    }

        /* ---- Card date row (inside card-right-side column, aligned with status chip) ---- */
    .card-date-row {
      display: grid !important; align-items: center; gap: 2px; text-align: right; padding-right: 0; max-width: 300px;
    }

    /* ---- Selection right-side column (status chip + dates stacked vertically) ---- */
    .selection-right-side {
      display: flex !important; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; max-width: 280px;
    }

    /* ---- Selection view date row (below status chip in selection mode) ---- */
    .selection-date-row {
      display: grid !important; align-items: center; gap: 2px; padding-right: 4px; max-width: 300px;
    }

    /* ---- Card date info text ---- */
    .date-info, .completed-info { font-size: 0.8rem; color: #1a1a2e !important; white-space: nowrap; max-width: 300px; }
    @media (prefers-color-scheme: dark) {
      .date-info, .completed-info { color: #bdbdbd !important; }
    }

    /* ---- Empty state ---- */
    .empty-state { text-align: center; padding: 48px 24px; color: #1a1a2e !important; }
    .empty-state p { margin-bottom: 16px; font-size: 1rem; }

    /* ---- Pagination ---- */
    .pagination { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 24px; padding: 16px 0; }
    .page-info { font-size: 0.85rem; color: #1a1a2e !important; font-weight: 500; min-width: 120px; text-align: center; }

    @media (prefers-color-scheme: dark) {
      .empty-state p { color: #9e9e9e; }
      .page-info { color: #bdbdbd; }
    }

    /* ---- Fade-in animation (CSS-only, no Angular animation module dependency) ---- */
    .fade-in {
      animation: fadeIn 0.25s ease-out both;
    }
    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(-8px); }
      to   { opacity: 1; transform: translateY(0); }
    }

    /* ---- Session checkbox (Material M3) ---- */
    .session-checkbox::ng-deep .mat-mdc-checkbox-mat-persistent { position: relative !important; }
    .session-checkbox { margin-right: 8px !important; flex-shrink: 0; display: inline-flex !important; align-items: center !important; vertical-align: middle !important; }
    @media (prefers-color-scheme: dark) { .session-checkbox { color: #e0a5ff !important; } }

    /* ---- Selection mode checkbox (outside history-item-wrapper — needs its own spacing) ---- */
    .selection-mode-checkbox { margin-left: 24px !important; margin-right: 16px !important; flex-shrink: 0; display: inline-flex !important; align-items: center !important; }

    /* ---- Card + delete row inside selection mode (horizontal layout) ---- */
    .selection-card-delete-area {
      display: flex !important; align-items: center; gap: 8px !important; width: 100%; flex: 1; min-width: 0;
    }

    /* ---- Dark mode adjustments ---- */
    @media (prefers-color-scheme: dark) {
      .page-header h2 { color: #e0e0e0; }
      .topic { color: #e0e0e0; }
    }
  `]
})
export class ResearchHistoryComponent implements OnInit {

  private historyService = inject(ResearchHistoryService);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  sessions = this.historyService.sessions;
  currentPage = this.historyService.currentPage;
  totalPages = this.historyService.totalPages;
  totalElements = this.historyService.totalElements;

  /** Selection state — tracks selected session IDs */
  private selectionSet: Set<string> = new Set();

  /** Whether the user has entered selection mode (checkboxes visible) even when nothing is selected yet */
  inSelectionMode = false;

  /** Computed signal for selection count */
  selectionCount = () => this.selectionSet.size;

  /** Computed signal: true when selection mode is active OR any item is selected */
  hasSelection = () => this.inSelectionMode || this.selectionSet.size > 0;

  /** True when actual selections exist (something is checked) — triggers delete button */
  hasActualSelections = () => this.selectionSet.size > 0;

   /** True when all items on the current page are selected — for toggle Select All / Deselect All label */
   isCurrentPageSelected = (): boolean => {
     const currentPageItems = this.sessions();
     if (currentPageItems.length === 0) return false;
     for (const item of currentPageItems) {
       if (!this.selectionSet.has(item.id)) {
         return false;
       }
     }
     return true;
   };

   ngOnInit(): void {
    this.historyService.loadHistory();
  }

  // ---- Helpers ----

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

  // ---- Navigation / search ----

  onSearch(event: Event): void {
    const input = (event.target as HTMLInputElement).value.trim();
    this.historyService.loadHistory(this.currentPage(), input);
  }

  loadPage(page: number): void {
    const search = this.historyService.searchTerm?.trim() || '';
    this.historyService.loadHistory(page, search);
  }

  // ---- Selection state management ----

  isSelected(sessionId: string): boolean {
    return this.selectionSet.has(sessionId);
  }

   enterSelectionMode(): void {
     this.inSelectionMode = true;
   }

   exitSelectionMode(): void {
     // Clear any selections and return to normal view
     this.selectionSet.clear();
     this.inSelectionMode = false;
   }

   toggleSelectAll(): void {
     if (this.isCurrentPageSelected()) {
       const currentPageItems = this.sessions();
       for (const item of currentPageItems) {
         this.selectionSet.delete(item.id);
       }
     } else {
       const currentPageItems = this.sessions();
       for (const item of currentPageItems) {
         this.selectionSet.add(item.id);
       }
     }
   }

   toggleSelect(event: any, item: any): void {
    if (event.checked) {
      this.selectionSet.add(item.id);
    } else {
      this.selectionSet.delete(item.id);
    }
  }

  // ---- Single session delete ----

  /** Open confirmation dialog for deleting a single session. */
  onDeleteSingle(item: any, event: Event): void {
    // Prevent navigation to detail view when clicking the delete button
    (event as Event).stopPropagation();

    this.dialog.open(DeleteConfirmationDialogComponent, {
      width: '480px',
      maxWidth: '90vw',
      data: {
        message: `This will permanently delete "${item.topic}" and all its associated steps. This cannot be undone.`,
        topics: [item.topic],
      } as DeleteConfirmData,
    }).afterClosed().subscribe((confirmed: boolean | undefined) => {
      if (confirmed === true) {
        this.historyService.deleteSession(item.id).subscribe({
          next: () => {
            this.snackBar.open('Session deleted successfully', 'Close', { duration: 3000, panelClass: ['success-snackbar'] });
            // Reload current page to reflect deletion
            const search = this.historyService.searchTerm?.trim() || '';
            this.historyService.loadHistory(this.currentPage(), search);
          },
          error: (err) => {
            console.error('[ResearchHistoryComponent] Single delete failed:', err);
            if (err?.status === 409) {
              this.snackBar.open('Cannot delete — session is still processing', 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
            } else {
              this.snackBar.open(`Failed to delete session`, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
            }
          },
        });
      }
    });
  }

  // ---- Bulk (multi-select) delete ----

  /** Open confirmation dialog for bulk deleting selected sessions. */
  onBulkDelete(): void {
    const sessionIds = Array.from(this.selectionSet);
    const topics = this.sessions()
      .filter((s: any) => this.selectionSet.has(s.id))
      .map((s: any) => s.topic);

    this.dialog.open(DeleteConfirmationDialogComponent, {
      width: '500px',
      maxWidth: '90vw',
      data: {
        message: `This will permanently delete ${sessionIds.length} selected research session${sessionIds.length !== 1 ? 's' : ''}. This action cannot be undone.`,
        topics: topics,
      } as DeleteConfirmData,
    }).afterClosed().subscribe((confirmed: boolean | undefined) => {
      if (confirmed === true) {
        this.historyService.bulkDeleteSessions(sessionIds).subscribe({
          next: () => {
            const plural = sessionIds.length !== 1 ? 's' : '';
            this.snackBar.open(`${sessionIds.length} session${plural} deleted successfully`, 'Close', { duration: 3000, panelClass: ['success-snackbar'] });
            // Clear selection and reload history list
            this.selectionSet.clear();
            const search = this.historyService.searchTerm?.trim() || '';
            this.historyService.loadHistory(this.currentPage(), search);
          },
          error: (err) => {
            console.error('[ResearchHistoryComponent] Bulk delete failed:', err);
            if (err?.status === 409) {
              this.snackBar.open('Cannot delete sessions — one or more are still processing. All operations rolled back.', 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
            } else {
              const plural = sessionIds.length !== 1 ? 's' : '';
              this.snackBar.open(`Failed to delete ${sessionIds.length} session${plural}`, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
            }
          },
        });
      }
    });
  }

}
