# Requirements: Report Reading Experience

## ADDED Requirements

### Requirement: Report document typography
Rendered research reports SHALL apply a `.report-doc` typographic style: a heading scale with `h2` section headers carrying an accent underline, body text at a comfortable line height (~1.75) within a constrained measure, and styled tables (borders, header-row background, zebra striping). Links, lists, blockquotes, code blocks, and horizontal rules SHALL be visually distinct from plain text. The style SHALL have dark-mode variants and SHALL NOT affect markdown rendered outside reports (e.g. step-list descriptions).

#### Scenario: Tables read as tables
- **WHEN** a report contains a markdown table
- **AND THEN** it renders with cell borders, a shaded header row, alternating row shading, and padded cells — not as unseparated text lines

#### Scenario: Step-list markdown is unaffected
- **WHEN** a research step description containing a list or table renders in the step list
- **AND THEN** it does not receive `.report-doc` styling

### Requirement: Table of contents with scroll-spy
A report with two or more headings SHALL display a table of contents listing its `h1`–`h3` sections. Selecting an entry SHALL smooth-scroll to that heading, and the entry for the section currently in view SHALL be highlighted while scrolling. On wide viewports the TOC SHALL be a sticky sidebar beside the report; on narrow viewports it SHALL stack above the report.

#### Scenario: Navigating a long report
- **WHEN** the user clicks a TOC entry
- **AND THEN** the view scrolls smoothly to that heading and the entry becomes highlighted

#### Scenario: Streaming report
- **WHEN** report content is still arriving via SSE and new sections appear
- **AND THEN** the TOC updates to include the new headings without duplicating or losing anchor links

#### Scenario: Short report
- **WHEN** a report has fewer than two headings
- **AND THEN** no TOC is shown

### Requirement: Citation markers rendered as chips
Numeric citation markers of the form `[n]` (one to three digits), including consecutive groups like `[1][2][3]`, SHALL render as small styled superscript chips. Markdown link-reference definitions (`[n]: url`) SHALL NOT be transformed.

#### Scenario: Cited claim
- **WHEN** report text contains `...as expected [1][2].`
- **AND THEN** both markers render as compact superscript chips and the sentence remains readable

#### Scenario: Reference definition preserved
- **WHEN** markdown contains a link-reference definition line `[9]: http://example.com`
- **AND THEN** it is not wrapped in a chip element

### Requirement: Reading comfort controls
The report view SHALL offer font-size control (small/medium/large) and a serif/sans-serif body toggle. Both preferences SHALL apply to the report text only, take effect immediately, and persist across sessions via `localStorage`.

#### Scenario: Preference persists
- **WHEN** the user selects large font + serif and later opens another report
- **AND THEN** both preferences are applied without re-selecting

### Requirement: Reading progress indicator
The report view SHALL show a thin fixed top bar indicating the reader's progress through the report. The bar SHALL be hidden when the reader is at the very start or has reached the end.

#### Scenario: Mid-report
- **WHEN** the reader has scrolled 50% through the report
- **AND THEN** the top bar is half filled

### Requirement: Full-page document view
A route `research/history/:sessionId/report` (auth-guarded) SHALL render a chrome-less article page for a historical session: topic title, creation/completion metadata, and the final report. A print stylesheet SHALL produce a clean paper layout — hiding the TOC, toolbar, progress bar, and navigation chrome, removing card shadows, and avoiding page breaks inside table rows. History-detail SHALL offer an "Open full page" action linking to this route.

#### Scenario: Completed session
- **WHEN** the user opens the document route for a COMPLETED session with a final report
- **AND THEN** the topic, dates, and fully styled report render in a centered article layout with TOC

#### Scenario: Session without report
- **WHEN** the session has no `finalReport`
- **AND THEN** the page shows an explanatory message instead of an empty article

#### Scenario: Failed load
- **WHEN** the session fetch fails
- **AND THEN** the page shows an error message with a retry action

#### Scenario: Printing
- **WHEN** the user prints from the document page (or the report toolbar)
- **AND THEN** the printed output contains only the article content, with tables kept intact across page breaks

## UNCHANGED Requirements
SSE streaming protocol and live render path, step-list rendering, history list/detail data loading, session deletion, follow-up flow — all unchanged by this delta.
