# Tasks: Web Content Cache

## Backend
- [x] Add `WebPageCacheEntry` entity (`url`, `content`, `source`, `fetchedAt`) in collection `web_page_cache`
- [x] Add `WebPageCacheRepository` (MongoRepository: `findByUrl`)
- [x] Add `WebContentCacheService`: `normalizeUrl`, `find(rawUrl)`, `store(rawUrl, content, source)`; `app.cache.enabled` + `app.cache.ttl` config; all failures swallowed with a warning log
- [x] `UrlReaderTool`: cache-first lookup; store non-empty results (full cleaned text, not the truncated reply); constructor takes the service
- [x] `WebSearchTool`: refactor parsers to structured `SearchHit {title, url, snippet}`; `formatHits` appends `Cached content:` for cached hits (4 000/hit, 16 000 total caps)
- [x] `application.yml`: add `app.cache.enabled: true`, `app.cache.ttl: P7D`
- [x] Add `WebPageCacheIndexInitializer` — explicit startup creation of the unique(`url`) and TTL(`fetchedAt`) indexes. Required because Spring Data MongoDB 5.x (Boot 4) defaults `auto-index-creation` to **false**, so `@Indexed` annotations are not honored automatically in this project

## Tests & Verification
- [x] Unit: `WebContentCacheServiceTest` — normalization matrix, find/store (mocked repo), expired entry rejected, store swallows exceptions, disabled no-op
- [x] Unit: `UrlReaderToolTest` (new) — hit → zero network; miss → fetch + stored; empty → not stored (stub HTTP server, same pattern as WebSearchToolTest)
- [x] Unit: extend `WebSearchToolTest` — cached content inlined, caps enforced, disabled → identical output
- [x] Integration: `WebPageCacheIntegrationTest` (@SpringBootTest, local Mongo) — round-trip, upsert overwrite, TTL index present with 7-day expiry
- [x] `mvn test` green (199 tests, 0 failures)
