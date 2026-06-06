package com.researchagent.service;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.repository.ResearchSessionRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class FollowUpService {

    private final ChatClient chatClient;
    private final ResearchSessionRepository sessionRepo;

    public FollowUpService(ChatClient chatClient, ResearchSessionRepository sessionRepo) {
        this.chatClient = chatClient;
        this.sessionRepo = sessionRepo;
    }

    /**
     * Handle a follow-up question on an existing research session.
     */
    public String processFollowUp(String sessionId, String question) {
        // Retrieve the original research session to get context
        ResearchSession session = sessionRepo.findById(java.util.UUID.fromString(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Research session not found: " + sessionId));

        String followUpPrompt = """
            You are a research assistant. The user has previously conducted research on this topic and
            received findings from the LLM. Now they're asking a follow-up question based on those findings.

            Previous Research Topic: %s
            Final Report Summary: %s

            Please answer their follow-up question by providing additional context, deeper insights, or
            pointing to specific sections of the previous research that are relevant.

            Follow-up Question: %s
            """;

        String prompt = followUpPrompt.formatted(
                session.getTopic(),
                session.getFinalReport() != null ? session.getFinalReport().substring(0, Math.min(session.getFinalReport().length(), 2000)) : "No report available",
                question
        );

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
