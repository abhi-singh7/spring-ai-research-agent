package com.researchagent.model.dto;

import java.util.List;

/**
 * Structured quality evaluation of the research findings before final report generation.
 *
 * <p>The LLM scores the accumulated sub-topic notes against the original topic on four dimensions,
 * flags specific issues (irrelevant content, shallow coverage, missing depth), and produces
 * recommendations that feed back into supplementary research and the synthesis prompt.</p>
 */
public record QualityEvalResult(
        int relevanceScore,   // 0-10: how well findings address the original topic
        int depthScore,       // 0-10: descriptive detail, specific facts, data points
        int coverageScore,    // 0-10: breadth across sub-topics relative to the topic
        int sourceQualityScore, // 0-10: authority, recency, and relevance of cited sources
        int overallScore,     // 0-10: composite judgment
        boolean passed,       // true if quality is sufficient to proceed to report generation
        List<String> issues,  // specific problems found (irrelevant content, gaps, shallow areas)
        List<String> recommendations // targeted improvements for supplementary research or report emphasis
) {
}
