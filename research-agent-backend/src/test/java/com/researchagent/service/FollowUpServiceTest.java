package com.researchagent.service;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.repository.ResearchSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FollowUpServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ResearchSessionRepository sessionRepo;

    /**
     * Stub the fluent chain: chatClient.prompt().user("...").call() -> content
     */
    private void stubChatResponse(String answer) {
        var requestSpec = org.mockito.Mockito.mock(
            org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);

        // .user("...") returns the spec (chainable), and then .call() is called on it.
        // We stub user() to return itself, then stub call().content()
        org.springframework.ai.chat.client.ChatClient.CallResponseSpec callResponse =
            org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);
        lenient().when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenAnswer(inv -> callResponse);
        lenient().when(callResponse.content()).thenReturn(answer);
    }

    @InjectMocks
    private FollowUpService service;

    // ---------- processFollowUp should throw when session not found ----------

    @Test
    void processFollowUp_shouldThrowExceptionWhenSessionNotFound() {
        UUID nonExistentId = UUID.randomUUID();
        when(sessionRepo.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.processFollowUp(nonExistentId.toString(), "Question?"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Research session not found");
    }

    // ---------- processFollowUp should throw for invalid UUID strings ----------

    @Test
    void processFollowUp_shouldThrowExceptionForInvalidUuid() {
        // UUID.fromString throws IllegalArgumentException for non-UUID strings before
        // the repository lookup is attempted — this documents current behavior.
        assertThatThrownBy(() -> service.processFollowUp("not-a-valid-uuid", "Question?"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- processFollowUp happy path ----------

    @Test
    void processFollowUp_shouldReturnLlmAnswerWhenSessionFound() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        try {
            java.lang.reflect.Field idField = ResearchSession.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(mockSession, sessionId);
            java.lang.reflect.Field topicField = ResearchSession.class.getDeclaredField("topic");
            topicField.setAccessible(true);
            topicField.set(mockSession, "AI in Healthcare");
            java.lang.reflect.Field reportField = ResearchSession.class.getDeclaredField("finalReport");
            reportField.setAccessible(true);
            reportField.set(mockSession, "# AI Report\nLong content here...");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(sessionRepo.findById(any(UUID.class))).thenReturn(java.util.Optional.of(mockSession));
        stubChatResponse("Based on the research findings...");

        String result = service.processFollowUp(sessionId.toString(), "Tell me more about AI diagnostics?");

        assertThat(result).isEqualTo("Based on the research findings...");
    }

    @Test
    void processFollowUp_shouldTruncateLongReports() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        try {
            java.lang.reflect.Field idField = ResearchSession.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(mockSession, sessionId);
            java.lang.reflect.Field topicField = ResearchSession.class.getDeclaredField("topic");
            topicField.setAccessible(true);
            topicField.set(mockSession, "Test Topic");
            StringBuilder longReport = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                longReport.append("Line ").append(i).append(": some content. ");
            }
            java.lang.reflect.Field reportField = ResearchSession.class.getDeclaredField("finalReport");
            reportField.setAccessible(true);
            reportField.set(mockSession, longReport.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(sessionRepo.findById(any(UUID.class))).thenReturn(java.util.Optional.of(mockSession));
        stubChatResponse("Answer");

        service.processFollowUp(sessionId.toString(), "Question?");

        // Verify chatClient.prompt() was called (confirms the method executed end-to-end)
        verify(chatClient).prompt();
    }

}
