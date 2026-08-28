## ADDED Requirements

### Requirement: Page content extraction falls back to body text when article selectors fail
When Jsoup cannot find any configured article/content HTML selectors in a target page, the `read_url` tool MUST fall back to extracting raw body text instead of returning "No readable content found."

#### Scenario: Article selector finds no matching elements
- **WHEN** the UrlReaderTool processes a URL and none of the configured selectors (`#article`, `#content`, `#main-content`, `.post-content`, etc.) match any element in the page
- **THEN** the tool falls back to extracting all text from the `<body>` tag

#### Scenario: Body text extraction returns trimmed content
- **WHEN** body text is extracted as a fallback
- **THEN** whitespace is normalized (collapsed), control characters are removed, and content is truncated to `maxContentLength` characters with an ellipsis appended if truncated

### Requirement: Paywalled or JS-rendered pages return partial content rather than errors
When the target page exists but returns no extractable article content (e.g., ScienceDirect paywalled articles, MDPI JS-rendered pages), the tool MUST return whatever text is present in the body element.

#### Scenario: Page has body elements with minimal content
- **WHEN** a Jsoup fetch of a page finds `<body>` elements containing only navigation or empty markup
- **THEN** the tool returns an empty string (not an error message), and the LLM uses search result snippets instead

### Requirement: URL reading respects HTTP timeout configuration
When fetching article content from URLs, the connection MUST use a configurable timeout to avoid hanging indefinitely on unresponsive pages.

#### Scenario: Unresponsive page triggers timeout
- **WHEN** a remote server does not respond within the configured timeout period (default 15 seconds)
- **THEN** the read_url tool returns an error message indicating the URL timed out, allowing the LLM to skip it and try another source

### Requirement: Redirect following is enabled for article content extraction
When fetching article content from URLs, HTTP redirects MUST be followed automatically.

#### Scenario: Target URL redirects to a different address
- **WHEN** a search result URL returns an HTTP redirect (301/302)
- **THEN** the fetch follows the redirect and extracts content from the final destination URL
