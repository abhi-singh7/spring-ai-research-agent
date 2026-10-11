# Design: Web Content Cache

## Storage model

One collection, `web_page_cache`, one document per normalized URL:

```java
@Document(collection = "web_page_cache")
public class WebPageCacheEntry {
    @Id String id;
    @Indexed(unique = true) String url;        // normalized — see below
    @ToString.Exclude String content;          // ≤ 15 000 chars (bounded by the readers already)
    String source;                             // "jsoup" | "firecrawl"
    @Indexed(expireAfter = "P7D") LocalDateTime fetchedAt;   // Mongo TTL index
}
```

- **Why a TTL index over an app-side purge**: MongoDB's built-in TTL monitor (60s cadence) deletes docs where `fetchedAt + 7d < now`. Zero scheduler code, no interaction with the existing `AbandonedSessionCleanupService`.
- **Indexes are created EXPLICITLY** (`config/WebPageCacheIndexInitializer`, a `@Component` running at startup): Spring Data MongoDB 5.x (Spring Boot 4) defaults `spring.data.mongodb.auto-index-creation` to **false** — the opposite of pre-5.0 behavior — so the `@Indexed` annotations above are documentation only; without the initializer the collection would run index-less. The initializer ensures both indexes via `MongoOperations.indexOps("web_page_cache").ensureIndex(...)`: unique on `url`, TTL on `fetchedAt` with `expireAfterSeconds = app.cache.ttl`. This keeps index creation deterministic and scoped to this feature without flipping the global default (which would start creating indexes for every other entity at boot).
- **Why the TTL field is `fetchedAt` (not a separate `expiresAt`)**: a TTL index expires at `fieldValue + expireAfter`, so indexing `fetchedAt` with `P7D` gives exactly "one week after fetch". A persisted `expiresAt = now + 7d` PLUS a P7D index would double the lifetime to 14 days. The in-app defensive check computes expiry as `fetchedAt + ttl`, so a doc is never served stale even if the TTL monitor lags.
- **Unique index on `url`**: makes `save()` an upsert-safe last-write-wins for concurrent research sessions hitting the same URL. Content for a given URL is reader-deterministic, so overwrites are harmless.

## URL normalization (cache key)

Applied in `WebContentCacheService.normalizeUrl`, used for BOTH lookup and store so keys always agree:

1. Parse with `java.net.URI`; on parse failure → return the trimmed raw string (still cacheable, just less canonical).
2. Lowercase scheme and host.
3. Drop the fragment.
4. Strip a trailing `/` from the path (but keep root `/`).
5. Drop tracking query params: `utm_*`, `fbclid`, `gclid`, `igsh`. Other query params are KEPT — they can change page content (e.g. `?page=2`).

Deliberately NOT done: http→https upgrade (some sites serve different content), redirect resolution (the reader follows redirects; we key on the URL as requested).

## Cache seam (`WebContentCacheService`)

```java
Optional<String> find(String rawUrl);        // normalized lookup + expiresAt > now check
void store(String rawUrl, String content, String source);  // upsert; blank content ignored
```

- Every method body is wrapped in try/catch: a Mongo failure logs a warning and degrades to "no cache" — the research pipeline must never fail because of the cache.
- `enabled=false` short-circuits both methods (find → empty, store → no-op).
- `ttl` (Duration, default `P7D`) is used for the in-app expiry check (`fetchedAt + ttl > now`). The annotation-level TTL index is fixed at `P7D`; if the configured `ttl` ever exceeds it, Mongo expires first — documented, acceptable.

## Integration points

### UrlReaderTool
```
read_url(url, maxLen):
  cached = cache.find(url)          # hit → return truncate(cached, maxLen) — NO network
  jsoup fetch → extractMainContent
  miss/empty → firecrawl /v2/scrape
  if result non-blank: cache.store(url, result, source)
  (unchanged error/empty semantics below that)
```
- Only NON-EMPTY results are cached — negative caching is out of scope (bot-walled pages may become readable later).
- The stored content is the full cleaned text (≤ 15 000 chars), NOT the `maxContentLength`-truncated reply, so a later call with a larger limit still benefits.

### WebSearchTool
Parsers (`parseFirecrawlJson`, `parseResultsJson`, `parseDuckDuckGoHtml`) change from "format text" to "return `List<SearchHit>`"; a single `formatHits(List<SearchHit>)` does the final formatting:

```
n. Title
   URL: ...
   Snippet: ...
   Cached content: <first 4000 chars of cached markdown>   ← only when cache.find(url) hits
```

Caps: 4 000 chars per hit, 16 000 chars total across all hits (8 results × 4k worst case fits the existing tool-output budget). Once the total cap is reached, remaining hits get no cached content. The decisive "no results" verdict path is unchanged.

## Config

```yaml
app:
  cache:
    enabled: true        # false → tools behave exactly as before this change
    ttl: P7D             # entry lifetime; must stay ≤ the TTL index duration (P7D)
```

## Testing strategy

- **Unit** — `WebContentCacheServiceTest`: normalization matrix, find/store with mocked repo, entry-expired-past-ttl rejection, store swallows repo exceptions, disabled mode no-ops.
- **Unit** — `UrlReaderToolTest` (new): stub HTTP server (same pattern as `WebSearchToolTest`) — cache hit → zero network hits; miss → fetch + entry stored; empty page → nothing stored.
- **Unit** — extend `WebSearchToolTest`: a hit with a cached entry gets `Cached content:` appended; per-hit/total caps respected; disabled cache → output identical to today.
- **Integration** — `WebPageCacheIntegrationTest` (`@SpringBootTest`, local Mongo): save/lookup round-trip, upsert-overwrite on same URL, TTL index presence verified via `MongoTemplate` index information.
