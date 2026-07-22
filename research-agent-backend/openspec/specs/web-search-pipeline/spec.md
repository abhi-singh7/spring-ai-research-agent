## Purpose

Define how the search pipeline handles SearXNG JSON format responses, DuckDuckGo HTML fallback parsing, MCP server health checks, and default result counts for unknown topics.

## Requirements

### Requirement: SearXNG queries use JSON response format
When searching via local Java HTTP calls to the SearXNG endpoint, the request MUST include `?format=json` parameter so that search results are returned as structured JSON with titles, URLs, and snippets.

#### Scenario: Request includes JSON format parameter
- **WHEN** a user searches for any topic through the web search tool
- **THEN** the SearXNG HTTP GET request URL MUST contain `&format=json` (or `?format=json`) as a query parameter

#### Scenario: Search results are structured JSON with extractable URLs and snippets
- **WHEN** the SearXNG server responds to a JSON-format search request
- **THEN** the response body contains an array of result objects, each with at least `title`, `url`, and optionally `content` (snippet) fields

#### Scenario: LLM receives structured results it can parse for tool calling
- **WHEN** the WebSearchTool returns SearXNG search results to the Spring AI ChatClient
- **THEN** the result content is valid JSON that the LLM can use to extract URLs and pass them to the `read_url` tool

### Requirement: DuckDuckGo fallback extracts URLs via Jsoup selectors
When SearXNG is unavailable and the code falls back to querying DuckDuckGo's HTML search page, the response MUST be parsed using Jsoup to reliably extract article URLs from search result links.

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
- **THEN** only existing components are used (SearXNG, DuckDuckGo, ollama_web_search.py) — no new HTTP clients or API keys needed
