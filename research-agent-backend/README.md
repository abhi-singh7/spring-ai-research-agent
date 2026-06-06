# Research Agent Backend

Spring Boot + Spring AI application for autonomous research.

## Prerequisites
- Java 21+
- PostgreSQL 15+
- OpenAI-compatible local LLM endpoint (e.g., Ollama)

## Configuration

Set these environment variables:
- `DB_USERNAME` — PostgreSQL username (default: postgres)
- `DB_PASSWORD` — PostgreSQL password (default: postgres)
- `OLLAMA_BASE_URL` — Local LLM API URL (default: http://localhost:1234/v1)
- `LLM_MODEL` — Model name (default: llama3.1)
- `TAVILY_API_KEY` — Optional Tavily Search API key for enhanced search

## Run
```bash
mvn spring-boot:run
```

The app will be available at http://localhost:8080.
