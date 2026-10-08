# Self-Hosted Firecrawl

Firecrawl is the **preferred search backend** for Research-Agent. It runs as a
self-hosted Docker stack on `http://localhost:3002` and is called over plain
HTTP (not MCP) by two backend tools:

| Tool | Endpoint | Purpose |
|------|----------|---------|
| `WebSearchTool` | `POST /v2/search` | First backend in every search chain (`firecrawl → ddg → ollama_web_search → tavily`) |
| `UrlReaderTool` | `POST /v2/scrape` | Fallback reader when Jsoup can't extract a page body (JS-heavy / bot-walled pages) |

The stack is defined in [`infra/firecrawl/docker-compose.yml`](../infra/firecrawl/docker-compose.yml).

## Stack layout

| Service | Image | Role |
|---------|-------|------|
| `api` | `ghcr.io/firecrawl/firecrawl` | HTTP API published on host port **3002** (search + scrape) |
| `playwright-service` | `ghcr.io/firecrawl/playwright-service` | Headless browser rendering for JS-heavy pages |
| `redis` | `redis:alpine` | Rate limiting / caching |
| `rabbitmq` | `rabbitmq:3-management` | Job queue transport |
| `nuq-postgres` | `ghcr.io/firecrawl/nuq-postgres` | Queue persistence (NuQ) |
| `searxng` | `searxng/searxng:latest` | Search provider backing `/v2/search` (Firecrawl's built-in DDG provider is blocked on datacenter IPs, so a local SearXNG instance is wired in via `SEARXNG_ENDPOINT`) |

All services sit on an internal `backend` bridge network; only the API port
(3002) is published to the host. There are **no persistent volumes** — queue
state and caches are ephemeral by design for a local dev box.

## Running the stack

```bash
cd infra/firecrawl
cp .env.example .env          # first time only
docker compose up -d          # pull + start; API on http://localhost:3002
docker compose ps             # verify all six services are running
docker compose logs -f api    # tail the API log while diagnosing
```

Stop with `docker compose down` (no data loss to worry about — nothing is persisted).

**Health check:** Firecrawl has **no `/health` endpoint** — any HTTP response
(even a 404) proves the server is listening:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3002/   # any code = up
```

A quick functional smoke test of both endpoints the backend uses:

```bash
curl -s -X POST http://localhost:3002/v2/search \
  -H 'Content-Type: application/json' \
  -d '{"query": "spring boot", "limit": 3}' | head -c 400

curl -s -X POST http://localhost:3002/v2/scrape \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com", "formats": ["markdown"]}' | head -c 400
```

## API contract (what Research-Agent consumes)

| Endpoint | Method | Body | Response (fields used) |
|----------|--------|------|------------------------|
| `/v2/search` | POST | `{"query": "...", "limit": N}` | `{"success": true, "data": {"web": [{"url", "title", "description"}, ...]}}` |
| `/v2/scrape` | POST | `{"url": "...", "formats": ["markdown"]}` | `{"success": true, "data": {"markdown": "...", "metadata": {...}}}` |

- `success: false` payloads and non-2xx responses are treated as backend
  failures — the search chain escalates to the next backend within the same
  tool call.
- Scrape can be slow on heavy pages (≈60s), which is why `UrlReaderTool` uses a
  60s read timeout for that one call versus the usual 10s.

## Configuration

Backend side (`research-agent-backend/src/main/resources/application.yml`):

```yaml
app:
  search:
    firecrawl-base-url: ${FIRECRAWL_BASE_URL:http://localhost:3002}
```

Stack side (`infra/firecrawl/.env`, copied from `.env.example`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `FIRECRAWL_VERSION` | `v2.11.325` | Release tag pinned for api / playwright-service / nuq-postgres images |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `postgres` | NuQ queue DB credentials (internal network only) |
| `NUM_WORKERS_PER_QUEUE` | `8` | Queue worker count |
| `CRAWL_CONCURRENT_REQUESTS` | `10` | Concurrent crawl/browser requests |
| `MAX_CONCURRENT_JOBS` | `5` | Max concurrent scrape jobs |
| `BROWSER_POOL_SIZE` | `5` | Playwright browser pool size |

The API runs with `USE_DB_AUTHENTICATION=false`, so **no API key is required**
for any client — including the Research-Agent backend.

## Using this Firecrawl as an MCP server for other LLMs

Yes. Firecrawl ships an official MCP server (`firecrawl-mcp` on npm) that can
point at **this self-hosted instance** instead of the cloud API. Any
MCP-compatible client (Claude Desktop, Claude Code, Cursor, OpenCode, …) can
use it — add to the client's MCP config:

```json
{
  "mcpServers": {
    "firecrawl": {
      "command": "npx",
      "args": ["-y", "firecrawl-mcp"],
      "env": {
        "FIRECRAWL_API_URL": "http://localhost:3002"
      }
    }
  }
}
```

Notes:

- **No `FIRECRAWL_API_KEY` needed** — the stack runs unauthenticated
  (`USE_DB_AUTHENTICATION=false`). If you later enable DB authentication, add
  the key here too.
- The MCP server is a **stdio process**, so it must run on a machine that can
  reach `http://localhost:3002` (i.e., this host). For a remote LLM client, the
  Firecrawl host/port must be network-reachable from wherever the MCP server
  process runs.
- The MCP server exposes scrape/crawl/search/map tools — the same capabilities
  Research-Agent calls directly over HTTP. Using it via MCP from another LLM
  does not interfere with the Research-Agent stack; both are just clients of
  the same API.

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| `WebSearchTool` verdict shows `firecrawl: ConnectException` | Stack is down — `docker compose ps`, then `docker compose up -d` |
| `/v2/search` returns empty `data.web` | SearXNG service down or its upstream engines are blocking the host IP — `docker compose logs searxng` |
| Scrape times out (~60s) | Heavy page; Playwright rendering is slow. Check `docker compose logs playwright-service`; raise `BROWSER_POOL_SIZE` if several jobs queue up |
| Stack won't start after a tag bump | Bump `FIRECRAWL_VERSION` in `.env` and verify the tag exists on ghcr; api/playwright/nuq-postgres must stay on the same version |
| Port 3002 already in use | Another process bound to 3002 — `ss -ltnp \| grep 3002` |

## Upgrading

1. Pick a release tag from the [Firecrawl releases](https://github.com/firecrawl/firecrawl/releases).
2. Set `FIRECRAWL_VERSION=<tag>` in `infra/firecrawl/.env`.
3. `docker compose pull && docker compose up -d` (recreates the three versioned containers).
4. Run the smoke tests above.
