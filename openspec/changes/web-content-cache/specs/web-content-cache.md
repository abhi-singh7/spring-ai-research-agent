# Requirements: Web Content Cache (delta)

## ADDED Requirements

### Requirement: Scraped page content is cached in MongoDB with a one-week TTL
Successfully read, non-empty page content SHALL be stored as a `WebPageCacheEntry`
(`url`, `content`, `source`, `fetchedAt`) in the `web_page_cache` collection, keyed by the
normalized URL. Entries SHALL expire automatically after ~1 week via a MongoDB TTL index on
`fetchedAt` (`expireAfter = P7D`). Empty reads and failed reads SHALL NOT be cached.

#### Scenario: Successful read is stored
- **WHEN** `read_url` fetches non-empty content for a URL not present in the cache
- **AND THEN** a document is saved to `web_page_cache` with the normalized URL, the full cleaned content (≤ 15 000 chars), and the reader source (`jsoup` or `firecrawl`)

#### Scenario: Empty page is not cached
- **WHEN** both Jsoup and Firecrawl yield no readable content for a URL
- **AND THEN** no cache document is created for that URL

#### Scenario: Entry expires after one week
- **WHEN** a cache entry's `fetchedAt` is older than the configured TTL (default 7 days)
- **AND THEN** the entry is treated as a miss by lookups, and MongoDB's TTL index deletes the document

### Requirement: URL normalization makes cache keys canonical
Cache lookups and stores SHALL use a normalized URL: lowercase scheme and host, fragment
removed, trailing path slash stripped, and tracking query parameters (`utm_*`, `fbclid`,
`gclid`, `igsh`) removed. All other query parameters SHALL be preserved. Unparseable URLs
SHALL fall back to their trimmed raw form.

#### Scenario: Equivalent URLs hit the same entry
- **WHEN** content is stored for `https://Example.com/Article/` and `read_url` is called with `http://example.com/article#section2?utm_source=x`
- **AND THEN** the cached content is returned without a network fetch

#### Scenario: Meaningful query params are kept distinct
- **WHEN** content is stored for `https://a.example/page` and a read is requested for `https://a.example/page?lang=fr`
- **AND THEN** the second URL is a cache miss (separate entry)

### Requirement: UrlReaderTool consults the cache before any network fetch
`read_url` SHALL check the cache first; a non-expired hit SHALL return the cached content
(truncated to the requested `maxContentLength`) without contacting Jsoup or Firecrawl.

#### Scenario: Cache hit skips all network I/O
- **WHEN** `read_url` is called for a URL with a non-expired cache entry
- **AND THEN** the cached content is returned and no HTTP request is made (neither Jsoup nor Firecrawl)

### Requirement: Search results embed cached content for known URLs
`WebSearchTool` SHALL consult the cache for each result URL and, for every non-expired hit,
append the cached content under that result (`Cached content:` lines), truncated to 4 000
chars per hit and 16 000 chars total across all hits. Results without a cache entry SHALL be
formatted exactly as before.

#### Scenario: Cached hit is inlined into search output
- **WHEN** a search returns a URL whose content is cached
- **AND THEN** the formatted result for that URL includes its cached content, letting the LLM answer without a `read_url` call

#### Scenario: Total inline cap is enforced
- **WHEN** more than four results have cached content of 4 000+ chars each
- **AND THEN** at most 16 000 chars of cached content in total appear in the search output

### Requirement: Cache failures never break the research pipeline
All cache reads and writes SHALL be failure-tolerant: a MongoDB error during lookup or store
SHALL be logged and degrade to uncached behavior (direct fetch / plain formatted results).
The cache SHALL be disable-able via `app.cache.enabled` (`false` → tools behave exactly as
before this change, no cache I/O at all).

#### Scenario: Mongo outage degrades gracefully
- **WHEN** the cache lookup throws
- **AND THEN** `read_url` proceeds to fetch the page directly and returns its content

#### Scenario: Cache disabled
- **WHEN** `app.cache.enabled` is `false`
- **AND THEN** neither tool performs any cache read or write
