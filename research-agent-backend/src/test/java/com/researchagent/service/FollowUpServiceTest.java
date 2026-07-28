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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowUpServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ResearchSessionRepository sessionRepo;

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

}
