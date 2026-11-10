package com.binocheck.service;

import com.binocheck.entity.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiServiceChatTest {

    private final GeminiService service =
            new GeminiService(WebClient.builder().build(), new ObjectMapper(), "test-model");

    private static ChatMessage message(String role, String text) {
        return ChatMessage.builder().role(role).messageText(text).build();
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> turn) {
        return ((List<Map<String, String>>) turn.get("parts")).get(0).get("text");
    }

    @Test
    void firstQuestionIsSentOnceWithTheRepositoryContext() {
        List<Map<String, Object>> contents = service.buildChatContents("CTX", List.of(), "What is this?");

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).get("role")).isEqualTo("user");
        assertThat(text(contents.get(0))).contains("CTX").endsWith("Question: What is this?");
    }

    @Test
    void followUpQuestionIsSentOnceAfterTheHistory() {
        List<ChatMessage> history = List.of(message("USER", "first"), message("MODEL", "answer"));

        List<Map<String, Object>> contents = service.buildChatContents("CTX", history, "second");

        assertThat(contents).extracting(turn -> turn.get("role")).containsExactly("user", "model", "user");
        assertThat(text(contents.get(0))).contains("CTX").endsWith("Question: first");
        assertThat(text(contents.get(1))).isEqualTo("answer");
        assertThat(text(contents.get(2))).isEqualTo("second");
        assertThat(contents).filteredOn(turn -> text(turn).contains("second")).hasSize(1);
    }
}
