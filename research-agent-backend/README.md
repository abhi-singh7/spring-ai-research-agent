# Research Agent Backend

Spring Boot + Spring AI application for autonomous research.

## Prerequisites
- Java 21+
- MongoDB running locally (default URI: `mongodb://localhost:27017/research-agent` — the database is created automatically)
- OpenAI-compatible local LLM endpoint (e.g., Ollama)

## Configuration

Set these environment variables:
- `OLLAMA_BASE_URL` — Local LLM API URL (default: http://localhost:1234 — no `/v1` suffix)
- `LLM_MODEL` — Model name (default: google/gemma-4-26b-a4b-qat)
- `TAVILY_API_KEY` — Optional Tavily Search API key for enhanced search

## Run
```bash
mvn spring-boot:run
```

The app will be available at http://localhost:8080.
