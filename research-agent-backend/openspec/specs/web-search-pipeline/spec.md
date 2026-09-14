## Purpose

Define how the search pipeline handles Firecrawl v2/search JSON responses, DuckDuckGo HTML fallback parsing, MCP server health checks, and default result counts for unknown topics.

## Requirements

### Requirement: Firecrawl search uses the v2/search JSON API
When searching via local Java HTTP calls to the self-hosted Firecrawl endpoint, the request MUST be a POST to `{app.search.firecrawl-base-url}/v2/search` with a JSON body `{"query": ..., "limit": N}`, and the response MUST be parsed from `{"success": true, "data": {"web": [{url, title, description}]}}`.

#### Scenario: Request is a JSON POST to /v2/search
- **WHEN** a user searches for any topic through the web search tool
- **THEN** the Firecrawl HTTP request MUST be a POST to `/v2/search` with a JSON body containing `query` and `limit`

#### Scenario: Search results are parsed from data.web with extractable URLs and snippets
- **WHEN** the Firecrawl server responds with `success: true`
- **THEN** the response's `data.web` array is read for `url`, `title`, and `description` fields, formatted as numbered "Title / URL / Snippet" lines

#### Scenario: Failure payloads trigger escalation instead of a result
- **WHEN** Firecrawl responds with `success: false`, a non-2xx status, or an empty `data.web` array
- **THEN** the backend is treated as failed and the routing chain escalates to the next backend within the same tool call

#### Scenario: LLM receives structured results it can parse for tool calling
- **WHEN** the WebSearchTool returns Firecrawl search results to the Spring AI ChatClient
- **THEN** the result content contains explicit URLs that the LLM can pass to the `read_url` tool

### Requirement: DuckDuckGo fallback extracts URLs via Jsoup selectors
When Firecrawl is unavailable and the code falls back to querying DuckDuckGo's HTML search page, the response MUST be parsed to reliably extract article URLs from search result links.

#### Scenario: Full DuckDuckGo HTML response is read (not truncated)
- **WHEN** a DuckDuckGo HTTP request returns successfully
- **THEN** all returned HTML content is captured without arbitrary line limits that would truncate results

#### Scenario: Jsoup extracts article URLs from search result links
- **WHEN** the DuckDuckGo HTML response contains `.url_header` class selectors on result article links
- **THEN** Jsoup selects these elements and extracts their `href` attributes as candidate URLs for reading

### Requirement: MCP server health check at startup time
On application startup, each configured MCP server connection MUST be verified and its tool availability status logged.

#### Scenario: MCP server with missing script file is reported as unavailable
- **WHEN** the ollama_web_search.py script path does not exist on disk
- **THEN** the application logs a clear error indicating which MCP tools are unavailable

#### Scenario: MCP server with invalid API key is reported at startup
- **WHEN** the MCP server starts but fails to authenticate (invalid `OLLAMA_API_KEY`)
- **THEN** the application logs that the MCP tool is unavailable and falls back to local Java tools

### Requirement: Default search result count increased for unknown topics
When searching for a topic where no keywords match "latest", "news", or "today" classifications, the default number of search results MUST be at least 5.

#### Scenario: Non-latest queries request more results than latest-specific queries
- **WHEN** a query does not contain "latest", "news", or "today" keywords
- **THEN** the search request requests at least 5 results (not fewer)

### Requirement: Tool name prefix for MCP tools is documented and consistent
All MCP-prefixed tool names MUST be prefixed with `mcp-` consistently, and this convention MUST be documented in the application configuration.

#### Scenario: MCP tool names are discoverable by their prefix
- **WHEN** Spring AI auto-registers MCP server tools
- **THEN** all MCP tool names follow the pattern `{server-name}-{tool-name}` with `mcp-` prefix

### Requirement: No new external dependencies added for search pipeline
The fix MUST not require adding any new Maven/Gradle dependencies or external service registrations beyond what is already configured.

#### Scenario: All search functionality works within existing infrastructure
- **WHEN** the application starts and receives a research request
- **THEN** only existing components are used (Firecrawl, DuckDuckGo, Ollama web search, Tavily) — no new HTTP clients or API keys needed
