import { Component, ElementRef, afterNextRender, input, signal, computed, viewChild } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MarkdownModule } from 'ngx-markdown';
import { extractToc, normalizeHeadingText, withCitationChips, TocEntry } from '../../utils/report-toc';

type FontSize = 'sm' | 'md' | 'lg';

const FONT_PX: Record<FontSize, number> = { sm: 15, md: 16, lg: 18 };
const LS_FONT_SIZE = 'ra.report.fontSize';
const LS_SERIF = 'ra.report.serif';

@Component({
  selector: 'report-viewer',
  standalone: true,
  imports: [MatCardModule, MatButtonModule, MatIconModule, MarkdownModule],
  template: `
    <div #shell class="report-shell" [style.--report-font-size.px]="fontSizePx()" [attr.data-serif]="serif()">
      <!-- Reading progress (fixed top bar) -->
      <div class="reading-progress" [class.visible]="progressVisible()" [style.width.%]="progress() * 100"></div>

      <!-- Table of contents — sticky sidebar on wide viewports, stacked above on narrow -->
      @if (toc().length >= 2) {
        <nav class="report-toc" aria-label="Table of contents">
          <div class="toc-header">On this page</div>
          <ul class="toc-list">
            @for (item of toc(); track item.id) {
              <li [class.active]="activeHeading() === item.id" [class.sub]="item.level >= 3">
                <a (click)="scrollToHeading(item.id); $event.preventDefault()">{{ item.text }}</a>
              </li>
            }
          </ul>
        </nav>
      }

      <mat-card class="report-card">
        <mat-card-header>
          <mat-card-title>{{ title() }}</mat-card-title>
          <div class="report-toolbar" aria-label="Reading controls">
            <button mat-icon-button [disabled]="fontSize() === 'sm'" (click)="decFont()" aria-label="Decrease font size">
              <mat-icon>remove_circle</mat-icon>
            </button>
            <button mat-icon-button [disabled]="fontSize() === 'lg'" (click)="incFont()" aria-label="Increase font size">
              <mat-icon>add_circle</mat-icon>
            </button>
            <button mat-icon-button [class.mat-primary]="serif()" (click)="toggleSerif()" title="Toggle serif font" aria-label="Toggle serif font">
              <mat-icon>font_download</mat-icon>
            </button>
            <button mat-icon-button (click)="print()" title="Print / Save as PDF" aria-label="Print report">
              <mat-icon>print</mat-icon>
            </button>
          </div>
        </mat-card-header>
        <mat-card-content class="report-content">
          <!-- Plain div wrapper: Angular 20 viewChild on a COMPONENT element returns the
               component instance, not an ElementRef — we need the DOM node to sync heading ids. -->
          <div #mdHost>
            <markdown class="report-doc" [data]="renderedContent()" (ready)="onMarkdownReady()"></markdown>
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .report-shell { position: relative; display: grid; gap: 20px; align-items: start; }

    /* Reading progress bar */
    .reading-progress {
      position: fixed; top: 0; left: 0; height: 3px; width: 0;
      background: var(--rp-primary); z-index: 2000;
      transition: width var(--rp-motion-fast) var(--rp-easing-standard);
      opacity: 0;
    }
    .reading-progress.visible { opacity: 1; }

    /* TOC */
    .report-toc { font-size: 0.85rem; }
    .toc-header {
      font-weight: 600; text-transform: uppercase; letter-spacing: 0.6px;
      font-size: 0.72rem; color: var(--rp-on-surface-variant); margin-bottom: 8px; padding: 0 10px;
    }
    .toc-list { list-style: none; margin: 0; padding: 0; border-left: 2px solid var(--rp-brand-16); }
    .toc-list li { margin: 0; }
    .toc-list a {
      display: block; padding: 5px 10px; color: var(--rp-on-surface-variant); text-decoration: none;
      border-left: 2px solid transparent; margin-left: -2px;
      transition: color var(--rp-motion-fast) var(--rp-easing-standard), background-color var(--rp-motion-fast) var(--rp-easing-standard);
    }
    .toc-list a:hover { color: var(--rp-primary); background: var(--rp-brand-04); }
    .toc-list li.active > a { color: var(--rp-primary); font-weight: 600; border-left-color: var(--rp-primary); background: var(--rp-brand-16); }
    .toc-list li.sub a { padding-left: 22px; }

    /* Card + toolbar */
    .report-card {
      --mdc-elevated-card-container-shape: 12px;
      overflow: hidden;
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-1);
    }
    .report-card mat-card-header { display: flex; align-items: center; gap: 8px; padding: 16px 24px 8px; }
    .report-card mat-card-title { flex: 1; font-weight: 600; color: var(--rp-on-surface); }
    .report-toolbar { display: flex; align-items: center; gap: 2px; }
    /* Material icon-button tokens (the hand-drawn .active pill is gone — the
       serif toggle carries class="mat-primary" when on). */
    .report-toolbar button {
      width: 34px; height: 34px;
      --mdc-icon-button-icon-color: var(--rp-on-surface-variant);
      --mdc-icon-button-state-layer-size: 34px;
    }
    .report-content { padding: 8px 24px 28px; }

    /* Wide viewports: sticky TOC sidebar */
    @media (min-width: 1100px) {
      .report-shell { grid-template-columns: 250px minmax(0, 1fr); }
      .report-toc { position: sticky; top: 16px; max-height: calc(100vh - 32px); overflow-y: auto; }
    }
  `]
})
export class ReportViewerComponent {
  content = input.required<string>();
  title = input('Research Report');

  private mdEl = viewChild.required<ElementRef<HTMLElement>>('mdHost');
  private shellEl = viewChild<ElementRef<HTMLDivElement>>('shell');

  toc = signal<TocEntry[]>([]);
  activeHeading = signal('');
  progress = signal(0);
  fontSize = signal<FontSize>(this.initialFontSize());
  serif = signal<boolean>(this.initialSerif());

  readonly renderedContent = computed(() => withCitationChips(this.content() ?? ''));
  readonly fontSizePx = computed(() => FONT_PX[this.fontSize()]);
  readonly progressVisible = computed(() => this.progress() > 0.005 && this.progress() < 0.995);

  private headingEls: HTMLElement[] = [];
  private lastSyncedContent = '\u0000'; // force first sync
  private scrollRaf = 0;

  constructor() {
    afterNextRender(() => {
      window.addEventListener('scroll', this.onScroll, { passive: true });
      window.addEventListener('resize', this.onScroll, { passive: true });
    });
  }

  /**
   * Fired by <markdown> AFTER its async parse + innerHTML replacement, on every
   * render (initial, data change, reload). This is the only safe moment to sync
   * heading ids with the TOC — doing it in afterRender would race the async DOM
   * update. Stateless by design: each render re-derives everything from the
   * markdown source, which is what makes this safe during SSE streaming.
   */
  onMarkdownReady(): void {
    const content = this.content() ?? '';
    if (content === this.lastSyncedContent) return;
    this.lastSyncedContent = content;
    const entries = extractToc(content);
    this.toc.set(entries);
    this.activeHeading.set(entries.length ? entries[0].id : '');
    this.syncHeadingIds(entries);
  }

  ngOnDestroy(): void {
    window.removeEventListener('scroll', this.onScroll);
    window.removeEventListener('resize', this.onScroll);
    if (this.scrollRaf) cancelAnimationFrame(this.scrollRaf);
  }

  // ---- reading controls -------------------------------------------------

  decFont(): void { this.setFontSize(this.fontSize() === 'lg' ? 'md' : 'sm'); }
  incFont(): void { this.setFontSize(this.fontSize() === 'sm' ? 'md' : 'lg'); }
  toggleSerif(): void { this.setSerif(!this.serif()); }
  print(): void { window.print(); }

  private setFontSize(size: FontSize): void {
    this.fontSize.set(size);
    ReportViewerComponent.store(LS_FONT_SIZE, size);
  }

  private setSerif(on: boolean): void {
    this.serif.set(on);
    ReportViewerComponent.store(LS_SERIF, on ? 'true' : 'false');
  }

  // ---- TOC + scroll-spy ---------------------------------------------------

  scrollToHeading(id: string): void {
    const el = this.mdEl()?.nativeElement.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /**
   * Assign ids to rendered headings by matching their text against the TOC
   * entries, consuming entries in document order. Text matching (instead of a
   * positional zip) stays correct even when the rendered heading set differs
   * from the source-level extraction — e.g. setext headings (`Title` + `===`),
   * headings inside blockquotes, or `#` lines inside code fences that marked
   * does not render as headings.
   */
  private syncHeadingIds(entries: TocEntry[]): void {
    const host = this.mdEl().nativeElement;
    if (!host) return;
    const els = Array.from(host.querySelectorAll('h1, h2, h3')) as HTMLElement[];
    const remaining = [...entries];
    for (const el of els) {
      const text = normalizeHeadingText(el.textContent || '');
      const idx = remaining.findIndex(e => normalizeHeadingText(e.text) === text);
      if (idx >= 0) {
        el.id = remaining.splice(idx, 1)[0].id;
      } else {
        // Rendered heading with no TOC entry — leave it unanchored.
        el.removeAttribute('id');
      }
    }
    this.headingEls = els.filter(el => el.id);
  }

  private onScroll = () => {
    if (this.scrollRaf) return;
    this.scrollRaf = requestAnimationFrame(() => {
      this.scrollRaf = 0;
      this.updateActiveHeading();
      this.updateProgress();
    });
  };

  private updateActiveHeading(): void {
    const els = this.headingEls;
    if (!els.length) return;
    let current = '';
    for (const el of els) {
      if (el.getBoundingClientRect().top <= 150) current = el.id;
      else break;
    }
    this.activeHeading.set(current);
  }

  private updateProgress(): void {
    const shell = this.shellEl()?.nativeElement;
    if (!shell) return;
    const rect = shell.getBoundingClientRect();
    const total = rect.height - window.innerHeight;
    if (total <= 0) { this.progress.set(0); return; }
    const passed = Math.min(Math.max(-rect.top, 0), total);
    this.progress.set(passed / total);
  }

  // ---- persistence --------------------------------------------------------

  private initialFontSize(): FontSize {
    const stored = ReportViewerComponent.read(LS_FONT_SIZE);
    return stored === 'sm' || stored === 'lg' ? stored : 'md';
  }

  private initialSerif(): boolean {
    return ReportViewerComponent.read(LS_SERIF) === 'true';
  }

  private static read(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
  }

  private static store(key: string, value: string): void {
    try { localStorage.setItem(key, value); } catch { /* storage unavailable — non-fatal */ }
  }
}
