import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MarkdownModule } from 'ngx-markdown';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ResearchService } from '../../../core/services/research.service';
import { FollowUpExchange } from '../../../core/models/research.model';

/**
 * Threaded follow-up chat for a completed research session.
 *
 * Renders the stored Q&A thread (user questions right-aligned, LLM answers left-aligned with
 * markdown rendering) above an input form. The server is the source of truth: submitting a
 * question returns the stored exchange (id + timestamp), which is appended to the local list —
 * no page reload, no refetch.
 */
@Component({
  selector: 'followup-thread',
  standalone: true,
  imports: [MatCardModule, FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MarkdownModule],
  template: `
    <mat-card class="followup-thread-card">
      <mat-card-header>
        <mat-card-title><mat-icon>forum</mat-icon> Follow-up conversation</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div class="thread">
          @for (exchange of exchanges(); track exchange.id) {
            <div class="message-row user">
              <div class="bubble user-bubble">{{ exchange.question }}</div>
            </div>
            <div class="message-row assistant">
              <div class="bubble assistant-bubble">
                <!-- .report-doc = the global report typography from styles.scss, so answers
                     read exactly like the report on the history page (incl. dark mode). -->
                <markdown class="report-doc" [data]="exchange.answer"></markdown>
              </div>
            </div>
          } @empty {
            <p class="empty-thread">No follow-up questions yet. Ask one below to dig deeper into this research.</p>
          }

          @if (isLoading()) {
            <div class="message-row assistant">
              <div class="bubble assistant-bubble waiting"><mat-spinner diameter="20"></mat-spinner></div>
            </div>
          }
        </div>

        @if (error()) {
          <p class="thread-error">{{ error() }}</p>
        }

        <form (ngSubmit)="onAsk()" class="composer">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Ask a follow-up question...</mat-label>
            <textarea matInput [(ngModel)]="question" name="question" rows="2"
                      [disabled]="isLoading()" (keydown.enter)="onEnter($event)"></textarea>
          </mat-form-field>
          <div class="composer-actions">
            @if (isLoading()) {
              <mat-spinner diameter="20"></mat-spinner>
            }
            <button mat-raised-button color="primary" type="submit" [disabled]="!question.trim() || isLoading()">
              <mat-icon>send</mat-icon> Ask
            </button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .followup-thread-card { border-radius: 12px !important; overflow: hidden; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.08); }
    .followup-thread-card mat-card-title { display: flex; align-items: center; gap: 8px; font-weight: 600; color: #0f0f23; }
    .followup-thread-card mat-card-title mat-icon { color: #6366f1; }

    .thread { display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px; min-height: 24px; }
    .empty-thread { color: #9e9e9e; font-size: 0.9rem; margin: 8px 0; }

    .message-row { display: flex; }
    .message-row.user { justify-content: flex-end; }
    .message-row.assistant { justify-content: flex-start; }

    .bubble { max-width: 78%; padding: 10px 14px; border-radius: 14px; font-size: 0.92rem; line-height: 1.5; }
    .user-bubble { background-color: #6366f1; color: #fff; border-bottom-right-radius: 4px; white-space: pre-wrap; }
    .assistant-bubble { background-color: #f1f3f9; color: #24292f; border-bottom-left-radius: 4px; }
    .assistant-bubble.waiting { display: flex; align-items: center; justify-content: center; padding: 12px; }

    /* Answer markdown reuses the global .report-doc typography — same look as the
       history page report. Drop its width/font caps so it fits the bubble. */
    .assistant-bubble .report-doc { max-width: none; font-size: inherit; line-height: 1.6; }
    .assistant-bubble .report-doc > :first-child { margin-top: 0; }
    .assistant-bubble .report-doc > :last-child { margin-bottom: 0; }

    .composer { display: flex; flex-direction: column; gap: 4px; }
    .full-width { width: 100%; }
    .composer-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }

    .thread-error { color: #c62828; font-size: 0.85rem; margin: 8px 0 0; }

    /* The Material theme is light-only, so in OS dark mode mat-card keeps its white
       surface — force a dark one, exactly like .report-card does in styles.scss, or
       the (dark-themed) text is unreadable. */
    @media (prefers-color-scheme: dark) {
      .followup-thread-card { background-color: #1c1f26 !important; border-color: #3a3f4a !important; }
      .followup-thread-card mat-card-title { color: #f0f2f5; }
      .user-bubble { background-color: #4f46e5; }
      .assistant-bubble { background-color: rgba(129, 140, 248, 0.1); color: #d5d9de; }
      .empty-thread { color: #8b919c; }
      .thread-error { color: #ef9a9a; }

      /* Composer input/label stay themed light — make them legible on the dark card. */
      :host ::ng-deep .mat-form-field { --mdc-outlined-textfield-outline-color: #3a3f4a; }
      :host ::ng-deep .mat-form-field-label,
      :host ::ng-deep .mat-form-field textarea { color: #d5d9de; }
    }
  `]
})
export class FollowUpThreadComponent {
  /** Session the thread belongs to (COMPLETED sessions only). */
  sessionId = input.required<string>();

  /** Exchanges already stored on the session — seeds the thread. */
  initialExchanges = input<FollowUpExchange[]>([]);

  /** Writable thread state: seeded from the input, grown as answers arrive. */
  exchanges = signal<FollowUpExchange[]>(this.initialExchanges());

  question = '';
  isLoading = signal(false);
  error = signal<string | null>(null);

  private researchService = inject(ResearchService);

  // Captured in the injection context (field initializer) so it can be passed to
  // takeUntilDestroyed() from event handlers, where no injection context is active.
  private destroyRef = inject(DestroyRef);

  onAsk(): void {
    const text = this.question.trim();
    if (!text || this.isLoading()) return;

    this.isLoading.set(true);
    this.error.set(null);

    this.researchService.submitFollowUp(this.sessionId(), text).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (exchange) => {
        // The server returns the exact stored object — append it, no refetch needed.
        this.exchanges.update(list => [...list, exchange]);
        this.question = '';
        this.isLoading.set(false);
      },
      error: () => {
        this.error.set('Failed to get an answer. Please try again.');
        this.isLoading.set(false);
      }
    });
  }

  /** Enter submits (Shift+Enter keeps a newline). */
  onEnter(event: Event): void {
    const keyboardEvent = event as KeyboardEvent;
    if (keyboardEvent.key === 'Enter' && !keyboardEvent.shiftKey) {
      keyboardEvent.preventDefault();
      this.onAsk();
    }
  }
}
