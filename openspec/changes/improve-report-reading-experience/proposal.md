# Proposal: Improve Report Reading Experience

## Why (Motivation)
Final research reports are long documents (30–50k characters) rendered by `ngx-markdown` with **default browser styling** — `report-viewer.component.ts` only tweaks h1–h3 colors and line-height. Inspection of a real 44.9k-char report from the running API shows what we actually render:

- 7× `##` + 5× `###` sections, ~200 list items, heavy `**bold**` usage
- **8 borderless tables** (domain weights, troubleshooting matrices) that look like plain text
- A References section that is a wall of numbered `Title — https://...` lines
- No way to navigate a 45k-char document, no typographic scale, no print/export path

The result reads as a flat wall of text rather than a formatted document.

## What Changes

### Added
- **Global `.report-doc` typography** in `styles.scss`: type scale (h2 section headers with accent underline), ~75ch measure, comfortable line-height, styled tables (borders, header background, zebra striping), list/link/code/blockquote styling, dark-mode variants, and a print stylesheet.
- **`shared/utils/report-toc.ts`** — pure helpers: TOC extraction from markdown source (h1–h3), deterministic slugify with duplicate suffixes, and the citation-chip transformation.
- **Table of contents sidebar with scroll-spy** inside `report-viewer`: sticky left column on wide viewports (stacked above the report on narrow ones), active section highlighted while scrolling, smooth-scroll on click.
- **Reading comfort controls** in the report toolbar: font size (S/M/L) and serif/sans toggle, persisted to `localStorage`.
- **Reading progress bar**: thin fixed top bar showing progress through the report; hidden at 0%/100%.
- **Full-page document view** — new route `research/history/:sessionId/report` rendering a chrome-less article page (topic title, metadata, report) that reuses `report-viewer`; "Open full page" action added to history-detail.

### Modified
- `shared/components/report-viewer/report-viewer.component.ts` — shell layout (TOC + card), toolbar, post-render heading-ID sync, scroll-spy, progress bar, citation-chip content transform.
- `features/history-detail/history-detail.component.ts` — "Open full page" action above the report.
- `app.routes.ts` — new document route (auth-guarded like its siblings).
- `styles.scss` — `.report-doc` typography + print CSS.

### Not changed (deliberately)
- **No backend changes** — reports stay markdown in Mongo; all work is client-side, so every existing history entry improves immediately.
- **No server-side HTML report generation** — rejected: only helps new sessions, requires sanitization of LLM-produced HTML, and duplicates the synthesis pipeline for marginal gain (see design.md).
- **Bare URLs in references** — marked's GFM default already autolinks them; they only need link styling.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `shared/components/report-viewer/report-viewer.component.ts` | Modified (major) | Medium — used by both active-research (streaming) and history-detail; heading sync must be streaming-safe |
| `styles.scss` | Modified (additive, scoped under `.report-doc`) | Low — no existing selectors touched |
| `shared/utils/report-toc.ts` | Added | Low — pure functions |
| `features/report-document/report-document.component.ts` | Added | Low — thin wrapper over report-viewer |
| `features/history-detail/history-detail.component.ts` | Modified (one action row) | Low |
| `app.routes.ts` | Modified (one route) | Low — placed before dynamic routes, auth-guarded |

Behavioral guarantees preserved: SSE streaming render path unchanged (report-viewer receives the same `content` input; TOC/progress simply update live as chunks arrive), step-list markdown rendering untouched, no API contract changes.
