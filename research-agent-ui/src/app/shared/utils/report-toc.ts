/**
 * Pure helpers for report reading: table-of-contents extraction, slugification,
 * and citation-chip transformation. No Angular dependencies so they are trivially testable.
 */

export interface TocEntry {
  id: string;
  text: string;
  level: number; // 1..3
}

const HEADING_RE = /^(#{1,3})\s+(.+?)\s*#*\s*$/gm;

/** Deterministic slug: lowercase, non-alphanumerics → '-', trimmed. */
export function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/** Strip inline markdown formatting so TOC labels read cleanly. */
function cleanHeadingText(raw: string): string {
  return raw
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1') // [text](url) → text
    .replace(/[*_`~]+/g, '')
    .replace(/&amp;/g, '&')   // marked decodes entities in rendered textContent
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"')
    .trim();
}

/** Normalize heading text for matching rendered DOM text against source text. */
export function normalizeHeadingText(raw: string): string {
  return cleanHeadingText(raw).replace(/\s+/g, ' ').trim().toLowerCase();
}

/**
 * Extract h1–h3 headings from markdown source in document order.
 * Duplicate slugs get -2, -3… suffixes (deterministic per content string).
 */
export function extractToc(markdown: string): TocEntry[] {
  const entries: TocEntry[] = [];
  const seen = new Map<string, number>();
  let match: RegExpExecArray | null;
  HEADING_RE.lastIndex = 0;
  while ((match = HEADING_RE.exec(markdown)) !== null) {
    const level = match[1].length;
    const text = cleanHeadingText(match[2]);
    if (!text) continue;
    let id = slugify(text);
    if (!id) id = 'section';
    const count = seen.get(id) ?? 0;
    seen.set(id, count + 1);
    if (count > 0) id = `${id}-${count + 1}`;
    entries.push({ id, text, level });
  }
  return entries;
}

/**
 * Turn numeric citation markers ([1], [1][2][3]) into styled superscript chips.
 * Runs on the markdown source BEFORE rendering; <sup> passes Angular's HTML
 * sanitizer allowlist. Link-reference definitions ("[9]: url") are left alone
 * via the (?!:) lookahead.
 */
export function withCitationChips(markdown: string): string {
  return markdown.replace(/\[(\d{1,3})\](?!:)/g, '<sup class="cite-ref">[$1]</sup>');
}
