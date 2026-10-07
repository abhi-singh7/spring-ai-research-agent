package com.researchagent.advisor;

import com.researchagent.model.entity.LLMLogs;
import com.researchagent.repository.LlmLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoggingAdvisor} — persistence content, session attribution from the advisor
 * context, stream aggregation, error handling, and resilience to repository failures.
 */
class LoggingAdvisorTest {

    private LlmLogRepository repository;
    private LoggingAdvisor advisor;

    @BeforeEach
    void setUp() {
        repository = mock(LlmLogRepository.class);
        when(repository.save(any(LLMLogs.class))).thenAnswer(inv -> inv.getArgument(0));
        advisor = new LoggingAdvisor(repository);
    }

    private ChatClientRequest request(UUID sessionId, String model) {
        Map<String, Object> context = sessionId == null
                ? Map.of()
                : Map.of(LoggingAdvisor.SESSION_ID_CONTEXT_KEY, sessionId);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).build();
        return new ChatClientRequest(new Prompt("You are a research assistant.", options), context);
    }

    private ChatClientRequest requestWithMessages(UUID sessionId, List<org.springframework.ai.chat.messages.Message> messages) {
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("test-model").build();
        return new ChatClientRequest(new Prompt(messages, options),
                Map.of(LoggingAdvisor.SESSION_ID_CONTEXT_KEY, sessionId));
    }

    private ChatClientResponse response(String text) {
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), Map.of());
    }

    @Test
    void call_success_savesLogWithSessionModelPromptAndResponse() {
        UUID session = UUID.randomUUID();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request(session, "test-model");
        when(chain.nextCall(request)).thenReturn(response("Hello there"));

        ChatClientResponse out = advisor.adviseCall(request, chain);

        assertThat(out.chatResponse().getResult().getOutput().getText()).isEqualTo("Hello there"); // response passed through untouched
        LLMLogs saved = captureSaved();
        assertThat(saved.getSessionId()).isEqualTo(session);
        assertThat(saved.getModel()).isEqualTo("test-model");
        assertThat(saved.getPrompt()).contains("You are a research assistant.");
        assertThat(saved.getResponse()).isEqualTo("Hello there");
        assertThat(saved.isStreaming()).isFalse();
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void call_withoutSessionId_savesNullSession() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response("ok"));

        advisor.adviseCall(request(null, "test-model"), chain);

        assertThat(captureSaved().getSessionId()).isNull();
    }

    @Test
    void call_error_savesErrorStatus_andRethrows() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> advisor.adviseCall(request(UUID.randomUUID(), "test-model"), chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        LLMLogs saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo("ERROR");
        assertThat(saved.getErrorMessage()).contains("boom");
        assertThat(saved.getResponse()).isNull();
    }

    @Test
    void stream_success_concatenatesChunksInOrder() {
        UUID session = UUID.randomUUID();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(response("Hel"), response("lo " ), response("world")));

        advisor.adviseStream(request(session, "test-model"), chain).blockLast();

        LLMLogs saved = captureSaved();
        assertThat(saved.getSessionId()).isEqualTo(session);
        assertThat(saved.isStreaming()).isTrue();
        // "Hel" + "lo " + "world" — chunks concatenated verbatim in arrival order
        assertThat(saved.getResponse()).isEqualTo("Hello world");
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void stream_error_savesPartialResponseWithErrorStatus() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any()))
                .thenReturn(Flux.concat(Flux.just(response("partial ")), Flux.error(new RuntimeException("stream down"))));

        assertThatThrownBy(() -> advisor.adviseStream(request(UUID.randomUUID(), "test-model"), chain).blockLast())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("stream down");

        LLMLogs saved = captureSaved();
        assertThat(saved.isStreaming()).isTrue();
        assertThat(saved.getResponse()).isEqualTo("partial ");
        assertThat(saved.getStatus()).isEqualTo("ERROR");
        assertThat(saved.getErrorMessage()).contains("stream down");
    }

    @Test
    void responseWithNoGeneration_savesEmptyText_withoutNpe() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        // A round that produced no result at all.
        ChatClientResponse empty = new ChatClientResponse(new ChatResponse(List.of()), Map.of());
        when(chain.nextCall(any())).thenReturn(empty);

        assertThatCode(() -> advisor.adviseCall(request(UUID.randomUUID(), "test-model"), chain)).doesNotThrowAnyException();

        assertThat(captureSaved().getResponse()).isEmpty();
    }

    @Test
    void toolCallOnlyGeneration_responseRendersToolCallMarkers() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        // An intermediate round of a tool-calling exchange: the model emitted only a tool call, no text.
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "web_search",
                        "{\"query\": \"vector databases RAG\"}")))
                .build();
        when(chain.nextCall(any())).thenReturn(
                new ChatClientResponse(new ChatResponse(List.of(new Generation(toolCallMessage))), Map.of()));

        advisor.adviseCall(request(UUID.randomUUID(), "test-model"), chain);

        assertThat(captureSaved().getResponse())
                .isEqualTo("[tool_call web_search({\"query\": \"vector databases RAG\"})]");
    }

    @Test
    void promptWithToolMessages_rendersToolCallsAndResults() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = requestWithMessages(UUID.randomUUID(), List.of(
                new SystemMessage("You are a research assistant."),
                new UserMessage("Research topic: RAG"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "web_search",
                                "{\"query\": \"RAG pipelines\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "web_search",
                                "[1] Spring AI docs - https://example.com/rag")))
                        .build()));
        when(chain.nextCall(any())).thenReturn(response("done"));

        advisor.adviseCall(request, chain);

        String prompt = captureSaved().getPrompt();
        assertThat(prompt).contains("system: You are a research assistant.");
        assertThat(prompt).contains("user: Research topic: RAG");
        assertThat(prompt).contains("Assistant [tool_call web_search({\"query\": \"RAG pipelines\"})]");
        assertThat(prompt).contains("Tool[web_search]: [1] Spring AI docs - https://example.com/rag");
    }

    @Test
    void repositoryFailure_isSwallowed_forBothCallAndStream() {
        when(repository.save(any(LLMLogs.class))).thenThrow(new RuntimeException("mongo down"));
        CallAdvisorChain callChain = mock(CallAdvisorChain.class);
        when(callChain.nextCall(any())).thenReturn(response("ok"));
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(any())).thenReturn(Flux.just(response("ok")));

        assertThatCode(() -> advisor.adviseCall(request(UUID.randomUUID(), "test-model"), callChain)).doesNotThrowAnyException();
        assertThatCode(() -> advisor.adviseStream(request(UUID.randomUUID(), "test-model"), streamChain).blockLast()).doesNotThrowAnyException();
    }

    private LLMLogs captureSaved() {
        ArgumentCaptor<LLMLogs> captor = ArgumentCaptor.forClass(LLMLogs.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
