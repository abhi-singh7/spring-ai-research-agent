# Proposal: Web Content Cache

## Why (Motivation)
Every research session re-fetches the same web pages from scratch:

1. **No content reuse** — `UrlReaderTool.read_url` performs a Jsoup fetch (15s timeout) and, for bot-walled or JS-heavy pages, an escalating Firecrawl `/v2/scrape` call (60s read timeout) on **every** invocation. Canonical references (documentation pages, standards, well-known articles) recur across sessions and even within one session (multiple sub-topics citing the same URL), yet each hit costs a full network round-trip against a local/self-hosted stack.
2. **Wasted LLM round-trips** — `WebSearchTool` returns only titles/URLs/snippets, so the LLM must follow up with one `read_url` tool call per page it wants. For pages whose content was already scraped in an earlier session, that is a full extra model round (prompt + tool call + result) for zero new information.
3. **Latency dominates** — on the local-LLM setup, network scraping (especially Firecrawl rendering) is often the slowest part of a research round; a cache hit turns it into a Mongo point lookup.

## What Changes

### Added
- **`WebPageCacheEntry`** — new `@Document(collection = "web_page_cache")` entity: `id`, normalized `url` (unique index), `content` (extracted readable text / scraped markdown, already bounded ≤ 15 000 chars by the readers), `source` (`jsoup` | `firecrawl`), `fetchedAt`.
- **TTL expiry** — `@Indexed(expireAfter = "P7D")` on `fetchedAt`: MongoDB's TTL monitor deletes entries ~1 week after fetch automatically (TTL index expires at `fieldValue + 7d`). No application-side purge job needed (same pattern as the existing `llm_logs` collection for index auto-creation).
- **`WebContentCacheService`** — the single seam both tools consult: `find(url)` (normalized lookup, defensive expiry check) and `store(url, content, source)` (upsert by normalized URL; failures swallowed — caching must never break the research pipeline). URL normalization: lowercase scheme/host, drop fragment, strip trailing path slash, drop tracking params (`utm_*`, `fbclid`, `gclid`, `igsh`).
- **Config** — `app.cache.enabled` (default `true`) and `app.cache.ttl` (Duration, default `P7D`) under `application.yml`.

### Modified
- **`UrlReaderTool.read_url`** — consults the cache FIRST; a non-expired hit returns the cached content immediately (no Jsoup, no Firecrawl). On a miss, after a successful NON-EMPTY fetch (from either reader), the content is stored. Empty/failing reads are never cached.
- **`WebSearchTool.search`** — result parsers are refactored from formatted-text to structured `SearchHit {title, url, snippet}` lists; before formatting, each hit's URL is consulted against the cache and, when a non-expired entry exists, the cached content (truncated: 4 000 chars per hit, 16 000 total) is appended under that result as `Cached content:` lines. The LLM can then answer directly without a `read_url` round-trip for those pages.
- **`application.yml`** — new `app.cache` block.

### Removed
- Nothing (behavior with `app.cache.enabled: false` is byte-identical to today).

## Impact
- **Affected specs**: `web-content-cache` (new capability).
- **Storage**: no migration — MongoDB auto-creates the collection and indexes from entity annotations on first use. Entries self-expire via the TTL index; worst case a week of entries × ~15 KB ≈ negligible.
- **API compatibility**: none — internal tools only; the `read_url`/`search` tool signatures are unchanged.
- **Failure modes**: all cache I/O is wrapped so Mongo outages degrade to today's behavior (direct fetch). The unique index on `url` makes concurrent upserts race-safe (last write wins; content is identical for a given URL+reader).
- **Staleness trade-off**: 1-week TTL means a page edited within the week may be served stale — accepted: research reports cite URLs as provenance, and sub-topic rounds already re-verify facts across multiple sources.
