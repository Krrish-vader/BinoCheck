package com.binocheck.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class WebClientTimeoutTest {

    @Test
    void aServerThatNeverAnswersFailsTheCallInsteadOfHangingIt() throws Exception {
        // Accepts the connection (via the OS backlog) but never sends a response.
        try (ServerSocket silentServer = new ServerSocket(0)) {
            WebClient client = WebClientConfig
                    .withTimeouts(WebClient.builder(), Duration.ofSeconds(2), Duration.ofMillis(300))
                    .baseUrl("http://localhost:" + silentServer.getLocalPort())
                    .build();

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> client.get().uri("/").retrieve().bodyToMono(String.class).block())
                            .isInstanceOf(WebClientRequestException.class));
        }
    }
}
