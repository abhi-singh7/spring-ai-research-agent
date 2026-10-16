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
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatRippleModule } from '@angular/material/core';
import { MatBadgeModule } from '@angular/material/badge';

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
      <button mat-stroked-button class="mat-primary" (click)="dialogRef.close(false)">Cancel</button>
      <button mat-raised-button class="mat-warn" (click)="dialogRef.close(true)">Delete Permanently</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .delete-confirm-content { min-width: 300px; max-width: 500px; }
    .topic-list { margin-top: 12px; padding-left: 8px; border-left: 2px solid var(--rp-outline); }
    .topic-item { display: block !important; font-size: 0.85rem !important; font-weight: 600 !important; padding: 2px 0 !important; white-space: nowrap !important; overflow: hidden !important; text-overflow: ellipsis !important; max-width: 400px !important; }
    /* Use Material M3's dialog supporting-text-color variable — adapts automatically to whichever theme the dialog is using (light or dark) */
    .delete-confirm-content .topic-list .topic-item,
    .mdc-dialog__content .delete-confirm-content .topic-item {
      color: var(--mdc-dialog-supporting-text-color) !important;
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
    MatChipsModule,
    MatTooltipModule,
    MatBadgeModule,
    MatRippleModule,
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
             <button mat-icon-button class="mat-primary" (click)="enterSelectionMode()" [attr.aria-label]="'Delete multiple items'">
               <mat-icon>delete_sweep</mat-icon>
             </button>
           }

          @if (inSelectionMode && hasActualSelections()) {
            <button mat-stroked-button class="mat-primary select-all-btn" (click)="toggleSelectAll()">
              {{ isCurrentPageSelected() ? 'Deselect All' : 'Select All' }}
            </button>
            <button mat-stroked-button class="mat-warn delete-btn" [disabled]="!hasActualSelections()"
                    [matBadge]="selectionCount()" matBadgeSize="small"
                    [matBadgeDescription]="selectionCount() + ' sessions selected'"
                    (click)="onBulkDelete()">
              Delete Selected
            </button>
          }

          <!-- Exit selection mode — always shown when in selection mode so user can cancel -->
          @if (inSelectionMode) {
            <span style="flex-shrink: 0;">&nbsp;</span>
            <button mat-stroked-button class="mat-primary cancel-selection-btn" (click)="exitSelectionMode()">Cancel</button>
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
                <!-- Material ripple: the card is a clickable surface, so press
                     feedback comes from MatRipple, not a hand-drawn :hover. -->
                <div matRipple class="item-ripple"></div>
                <mat-card-content class="card-body">
                  <span class="topic">{{ item.topic }}</span>
                  <div class="card-right-side">
                    <mat-chip class="status-chip" [matTooltip]="statusHint(item.status)"
                          [class.pending]="item.status === 'PENDING'"
                          [class.processing]="item.status === 'PROCESSING'"
                          [class.completed]="item.status === 'COMPLETED'"
                          [class.failed]="item.status === 'FAILED'"
                          [class.cancelled]="item.status === 'CANCELLED'">
                      {{ getStatusLabel(item.status) }}
                    </mat-chip>

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
              <button mat-icon-button class="mat-warn delete-btn-outside normal-view-delete"
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
                <div matRipple class="item-ripple"></div>
                <mat-card-content class="card-body">
                  <span class="topic">{{ item.topic }}</span>
                  <div class="card-right-side">
                    <mat-chip class="status-chip" [matTooltip]="statusHint(item.status)"
                          [class.pending]="item.status === 'PENDING'"
                          [class.processing]="item.status === 'PROCESSING'"
                          [class.completed]="item.status === 'COMPLETED'"
                          [class.failed]="item.status === 'FAILED'"
                          [class.cancelled]="item.status === 'CANCELLED'">
                        {{ getStatusLabel(item.status) }}
                      </mat-chip>

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
              <button mat-icon-button class="mat-warn delete-btn-outside"
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
          <a routerLink="/" mat-raised-button class="mat-primary">Start New Research</a>
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
    .page-header h2 { margin: 0; font-size: 1.75rem; font-weight: 700; color: var(--rp-on-surface) !important; }
    .session-count { font-size: 0.9rem; color: var(--rp-on-surface-variant) !important; font-weight: 400; }

    /* ---- Search bar ---- */
    .search-bar { width: 100%; margin-bottom: 24px; }

    /* ---- Action toolbar ---- */
    .action-toolbar {
      display: flex; align-items: center; gap: 16px; padding: 12px 18px;
      background-color: var(--rp-info-soft); border-radius: 8px; margin-bottom: 16px; min-height: 44px;
    }
    .delete-btn { padding: 0 24px; min-width: auto; font-weight: 500; letter-spacing: 0.3px; }
    .cancel-selection-btn {
      margin-left: 8px !important;
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
    .item-card {
      /* Material card API: shape and elevation are the card's own tokens. */
      --mdc-elevated-card-container-shape: 12px;
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-1);
      --mat-ripple-color: var(--rp-primary);
      position: relative;
      overflow: hidden;
      cursor: pointer;
      transition: transform var(--rp-motion-standard) var(--rp-easing-standard);
    }
    .item-card:hover {
      /* Hover = one elevation step up, on Material's motion curve. */
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-4);
      transform: translateY(-2px);
    }
    /* The ripple fills the card; later siblings stay hit-testable above it. */
    .item-ripple {
      position: absolute;
      inset: 0;
      border-radius: inherit;
    }

    /* ---- Selection view row layout (checkbox + topic left-aligned, status chip + dates right-aligned) ---- */
    .delete-btn-outside {
      flex-shrink: 0;
      display: inline-flex !important;
      align-items: center !important;
      justify-content: center !important;
      width: 48px !important;
      height: 48px !important;
      /* Material icon-button API: state-layer size + brand-tinted hover. */
      --mdc-icon-button-state-layer-size: 48px;
      --mat-icon-button-hover-state-layer-opacity: 0.16;
    }

    /* ---- Normal view card ---- */
    a.history-item { text-decoration: none; color: inherit; }

    /* ---- Selected card styling (normal view) ---- */
    .item-card.selected {
      --mdc-elevated-card-container-color: var(--rp-info-soft);
    }

    /* ---- Selected row styling (selection mode — no card involved) ---- */
    .history-item-wrapper.selected { background-color: var(--rp-info-soft) !important; border-radius: 8px; }

    /* ---- Card body (topic left, status+dates right) ---- */
    .card-body { display: flex !important; align-items: center; justify-content: space-between; gap: 16px; min-height: 0; padding-right: 4px; }
    .card-right-side { display: inline-flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; max-width: 280px; }


    /* ---- Topic text ---- */
    .topic { font-weight: 600; font-size: 0.95rem; color: var(--rp-on-surface) !important; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }

    /* ---- Selection row (checkbox + topic) ---- */
    .selection-row { display: flex; align-items: center; gap: 12px; padding: 14px 18px 0 14px; }

    /* ---- Status chip (Material mat-chip; colors set through the chip's own tokens) ---- */
    .status-chip {
      --mdc-chip-container-height: 28px;
      --mdc-chip-container-shape-radius: 14px;
      --mdc-chip-label-text-size: 0.75rem;
      --mdc-chip-label-text-weight: 500;
      flex-shrink: 0;
    }
    .status-chip.pending { --mdc-chip-elevated-container-color: var(--rp-warning-container); --mdc-chip-label-text-color: var(--rp-on-warning-container); }
    .status-chip.processing { --mdc-chip-elevated-container-color: var(--rp-primary-container); --mdc-chip-label-text-color: var(--rp-on-primary-container); }
    .status-chip.completed { --mdc-chip-elevated-container-color: var(--rp-success-container); --mdc-chip-label-text-color: var(--rp-on-success-container); }
    .status-chip.failed { --mdc-chip-elevated-container-color: var(--rp-error-container); --mdc-chip-label-text-color: var(--rp-on-error-container); }
    .status-chip.cancelled { --mdc-chip-elevated-container-color: var(--rp-surface); --mdc-chip-label-text-color: var(--rp-on-surface-variant); }

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
    .date-info, .completed-info { font-size: 0.8rem; color: var(--rp-on-surface-variant) !important; white-space: nowrap; max-width: 300px; }

    /* ---- Empty state ---- */
    .empty-state { text-align: center; padding: 48px 24px; color: var(--rp-on-surface-variant) !important; }
    .empty-state p { margin-bottom: 16px; font-size: 1rem; }

    /* ---- Pagination ---- */
    .pagination { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 24px; padding: 16px 0; }
    .page-info { font-size: 0.85rem; color: var(--rp-on-surface-variant) !important; font-weight: 500; min-width: 120px; text-align: center; }

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

    /* ---- Selection mode checkbox (outside history-item-wrapper — needs its own spacing) ---- */
    .selection-mode-checkbox { margin-left: 24px !important; margin-right: 16px !important; flex-shrink: 0; display: inline-flex !important; align-items: center !important; }

    /* ---- Card + delete row inside selection mode (horizontal layout) ---- */
    .selection-card-delete-area {
      display: flex !important; align-items: center; gap: 8px !important; width: 100%; flex: 1; min-width: 0;
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

  /** Hover affordance for the status chip: what the user can actually do with this session. */
  statusHint(status: string): string {
    const hints: Record<string, string> = {
      PENDING: 'Queued — the pipeline has not started yet',
      PROCESSING: 'Open this session to watch its steps and the live report',
      COMPLETED: 'Report available',
      FAILED: 'Failed — open it to resume the research',
      CANCELLED: 'Cancelled — open it to resume the research'
    };
    return hints[status] || status;
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
