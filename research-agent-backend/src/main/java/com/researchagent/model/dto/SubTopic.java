package com.researchagent.model.dto;

import java.util.List;

/**
 * A sub-topic planned by the breakdown stage, enriched with concrete web search queries.
 *
 * <p>This type is deserialized from the planner LLM's structured output: Spring AI generates a
 * JSON schema from it (via {@code BeanOutputConverter}) and appends it to the prompt, so the field
 * names are part of the model contract.</p>
 */
public record SubTopic(int id, String title, String description, List<String> searchQueries) {
}
