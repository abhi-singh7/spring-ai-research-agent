import { AfterViewInit, Component, DestroyRef, inject, input, signal } from '@angular/core';
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
                <markdown class="followup-answer-md" [data]="exchange.answer"></markdown>
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
    /* Like the step-list / session-info cards on the history page: a plain themed
       (white) mat-card in BOTH light and dark OS modes — no forced dark surface,
       so the themed dark text (title, input, label) is always readable. */
    .followup-thread-card { border-radius: 12px !important; overflow: hidden; box-shadow: 0 2px 8px rgba(99, 102, 241, 0.08); }
    .followup-thread-card mat-card-title { display: flex; align-items: center; gap: 8px; font-weight: 600; }
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

    /* Answer markdown: fixed dark-on-light typography, readable on the light bubble in
       both OS modes. Deliberately NOT .report-doc — that class flips to light text in
       OS dark mode and would vanish on the light bubble. */
    .assistant-bubble .followup-answer-md { line-height: 1.6; color: #24292f; }
    .assistant-bubble .followup-answer-md > :first-child { margin-top: 0; }
    .assistant-bubble .followup-answer-md > :last-child { margin-bottom: 0; }
    .assistant-bubble .followup-answer-md p { margin: 0 0 0.7em; }
    .assistant-bubble .followup-answer-md :is(h1, h2, h3, h4) { color: #0f0f23; font-size: 1.05em; margin: 0.8em 0 0.4em; }
    .assistant-bubble .followup-answer-md ul,
    .assistant-bubble .followup-answer-md ol { padding-left: 1.5em; margin: 0.4em 0 0.7em; }
    .assistant-bubble .followup-answer-md a { color: #4f46e5; word-break: break-word; }
    .assistant-bubble .followup-answer-md code { background: rgba(99, 102, 241, 0.08); padding: 1px 5px; border-radius: 4px; font-size: 0.88em; }
    .assistant-bubble .followup-answer-md pre { background: #fff; border: 1px solid #e3e6ea; border-radius: 8px; padding: 10px 12px; margin: 0.7em 0; overflow-x: auto; }
    .assistant-bubble .followup-answer-md pre code { background: none; padding: 0; }

    .composer { display: flex; flex-direction: column; gap: 4px; }
    .full-width { width: 100%; }
    .composer-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }

    .thread-error { color: #c62828; font-size: 0.85rem; margin: 8px 0 0; }
  `]
})
export class FollowUpThreadComponent implements AfterViewInit {
  /** Session the thread belongs to (COMPLETED sessions only). */
  sessionId = input.required<string>();

  /** Exchanges already stored on the session — seeds the thread. */
  initialExchanges = input<FollowUpExchange[]>([]);

  /** Writable thread state: seeded in ngAfterViewInit, grown as answers arrive. */
  exchanges = signal<FollowUpExchange[]>([]);

  question = '';
  isLoading = signal(false);
  error = signal<string | null>(null);

  private researchService = inject(ResearchService);

  // Captured in the injection context (field initializer) so it can be passed to
  // takeUntilDestroyed() from event handlers, where no injection context is active.
  private destroyRef = inject(DestroyRef);

  ngAfterViewInit(): void {
    // Inputs are bound AFTER construction — reading initialExchanges() in a field
    // initializer always returns the default []. Seed the thread here instead, so
    // persisted exchanges render on page load.
    this.exchanges.set(this.initialExchanges());
  }

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
