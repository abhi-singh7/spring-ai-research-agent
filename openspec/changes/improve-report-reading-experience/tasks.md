# Tasks: Improve Report Reading Experience

## Implementation
- [x] Add `.report-doc` typography to `styles.scss`: heading scale (h2 accent underline), line-height/measure, tables (borders/header bg/zebra), lists, links (`word-break`), blockquotes, code/pre, hr, dark-mode variants
- [x] Add print stylesheet: hide TOC/toolbar/progress/top-bar/back-link, remove card shadow, full-width content, `tr { page-break-inside: avoid }`
- [x] Add `shared/utils/report-toc.ts`: `extractToc(markdown)` (h1–h3, slugify + duplicate suffixes), `withCitationChips(markdown)` (`[n]` → `<sup class="cite-ref">`, `(?!:)` guard)
- [x] Rework `report-viewer.component.ts`: shell grid (sticky TOC sidebar ≥1100px, stacked below), toolbar (font S/M/L, serif toggle, print), progress bar, citation-chip content transform, heading-ID sync on the `<markdown>` `ready` event (count-guarded — `afterRender` races the component's async parse), rAF-throttled scroll-spy
- [x] Persist font size + serif to `localStorage` (`ra.report.fontSize`, `ra.report.serif`)
- [x] Add `features/report-document/report-document.component.ts`: fetch session, article shell (top bar with back + print, topic, dates), render `<report-viewer>`; loading/error/no-report states
- [x] Register route `research/history/:sessionId/report` (authGuard) in `app.routes.ts` before the dynamic routes
- [x] Add "Open full page" action to `history-detail.component.ts` above the report section

## Verification
- [x] `npx ng build` succeeds with no template/type errors
- [x] TOC/citation logic verified against a real 44.9k-char report: 12 clean entries (e.g. `research-gaps-confidence`), duplicate slugs suffixed (`intro`, `intro-2`), `[1][2]` → chips, link-reference defs untouched, 4-digit markers untouched
- [x] Headless-browser verified (Chrome + puppeteer-core against `ng serve`): 12/12 heading IDs assigned on the real 48.8k-char report, TOC click scrolled 0→31914px to the right section, scroll-spy highlighted the clicked section, dark-mode card surface renders readable
- [x] Root-caused via CDP: Angular 20 `viewChild` on a component element returns the component instance (no `.nativeElement`) — fixed by querying a plain `#mdHost` div wrapper; gotcha recorded in AGENTS.md
- [ ] Manual: font/serif controls persist after reload; printing hides chrome and keeps table rows intact
- [ ] Manual: active-research streaming view — TOC grows live as sections arrive, no broken anchors
- [ ] Manual: step-list markdown rendering unchanged

## Deferred / Follow-ups (not part of this change)
- Per-section "share/copy" links, dark-mode toggle independent of OS preference
- Server-side HTML report generation (rejected in design.md; revisit only if LLM output quality justifies it)
