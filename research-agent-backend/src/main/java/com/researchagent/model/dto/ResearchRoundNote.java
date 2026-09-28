package com.researchagent.model.dto;

import java.util.List;

/**
 * One research round's output, typed from the researcher LLM's structured output.
 *
 * <p>Spring AI generates a JSON schema from this record and appends it to the round prompt; the
 * model's final answer (after its tool calls) is parsed into it. The orchestrator renders the note
 * back to the markdown shape used for step content, follow-up rounds and synthesis input.</p>
 */
public record ResearchRoundNote(List<String> findings, List<SourceRef> sourcesConsulted, List<String> openQuestions) {

    /** A page actually read during the round: its title and full URL (feeds References provenance). */
    public record SourceRef(String title, String url) {
    }
}
