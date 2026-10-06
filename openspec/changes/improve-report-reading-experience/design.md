# Design: Improve Report Reading Experience

## Decision 1: client-side only — no server-generated HTML reports

The alternative was having the LLM emit an HTML report (rendered in a sanitized "mini UI"). Rejected because:

- It would only help **new** sessions; the 20 existing history entries (the ones the user is reading today) stay plain.
- LLM-produced HTML needs DOMPurify-level sanitization and a second rendering path to maintain.
- The readability problems (typography, tables, navigation, print) are all presentation concerns that CSS + a TOC solve on the markdown we already have.

All changes are therefore client-side; reports remain markdown end-to-end.

## Decision 2: one global `.report-doc` stylesheet as the single source of truth

Typography lives in `styles.scss` under a `.report-doc` class applied to the `<markdown>` element, rather than component-scoped styles. Reasons:

- Two surfaces render reports (embedded `report-viewer` in history-detail/active-research, and the new chrome-less document page). A shared class means one implementation.
- Inline `styles:` arrays in standalone components cannot `@use` a partial; global SCSS avoids duplication without build-config changes.
- Scoped under `.report-doc`, so step-list markdown and everything else are untouched.

## Decision 3: stateless post-render heading-ID sync (not a custom MarkedRenderer)

The TOC needs anchor IDs on rendered headings. A stateful custom `MarkedRenderer` that slugifies headings would keep per-render counters in a shared singleton — during streaming, every chunk re-renders and stale/duplicate slugs would corrupt the TOC. Instead:

1. Extract TOC entries from the **markdown source** (regex over `#{1,3}` lines) — deterministic per content string.
2. After each render (`afterRender`, guarded by last-synced content identity), assign `id` attributes to the container's rendered `h1,h2,h3` elements **in document order**.
3. If the rendered heading count ≠ extracted count (e.g. a `#` line inside a fenced code block), skip ID assignment for that render — TOC links simply don't scroll; no crash, self-heals on the next chunk.

This is streaming-safe: each content change re-derives everything from scratch.

## Decision 4: citation chips via pre-render markdown transform

`[1][2]` citation markers become `<sup class="cite-ref">[1]</sup>` chips by transforming the **markdown string before** it reaches `<markdown>`. Verified against `@angular/core`'s sanitizer allowlist (`core.mjs`, INLINE_ELEMENTS) that `sup` passes `DomSanitizer.sanitize(SecurityContext.HTML, ...)` — ngx-markdown's default sanitization path. The transform skips link-reference definitions via a `(?!:)` lookahead so `[9]: http://...` style lines are untouched.

## Decision 5: bare URLs need no transform

Verified against the installed `marked`: GFM autolinking is on by default, so `https://...` in reference lines already renders as `<a href>`. Only link styling (color, `word-break`) is needed.

## Decision 6: document page is a thin wrapper over report-viewer

The new `research/history/:sessionId/report` route does not re-implement TOC/progress/font controls — it fetches the session and renders `<report-viewer [content]="finalReport">` inside a minimal article shell (top bar with back link + print, topic title, date metadata). All reading logic stays in one component.

## Decision 7: scroll-spy via rAF-throttled scroll listener

A passive `scroll` listener throttled with `requestAnimationFrame` computes the active heading (last heading whose top ≤ 150px) and the progress bar value (report shell rect vs viewport). Chosen over `IntersectionObserver` because it is deterministic for "last heading above threshold" semantics, needs no re-observation on every streaming re-render, and the per-frame work is trivial (≤ a dozen headings).

## Persistence

Font size (`ra.report.fontSize`: `sm|md|lg`) and serif preference (`ra.report.serif`: `true|false`) persist to `localStorage` and are read once at construction. No backend involvement.
