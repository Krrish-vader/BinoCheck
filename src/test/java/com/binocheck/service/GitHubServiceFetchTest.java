package com.binocheck.service;

import com.binocheck.exception.GitHubApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubServiceFetchTest {

    private static GitHubService serviceBackedBy(ExchangeFunction github) {
        return new GitHubService(WebClient.builder().exchangeFunction(github).build(), new ObjectMapper());
    }

    @Test
    void manifestFilesAreFetchedConcurrentlyAndKeepTheirOrder() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        ExchangeFunction github = request -> {
            String path = request.url().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return Mono.defer(() -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                ClientResponse response = "missing.txt".equals(name)
                        ? ClientResponse.create(HttpStatus.NOT_FOUND).build()
                        : ClientResponse.create(HttpStatus.OK).body("content of " + name).build();
                return Mono.just(response)
                        .delayElement(Duration.ofMillis(150))
                        .doFinally(signal -> inFlight.decrementAndGet());
            });
        };

        Map<String, String> contents = serviceBackedBy(github)
                .fetchFileContents("o", "r", List.of("pom.xml", "missing.txt", "package.json", "go.mod"));

        assertThat(maxInFlight.get()).isGreaterThan(1);
        assertThat(contents.keySet()).containsExactly("pom.xml", "package.json", "go.mod");
        assertThat(contents).containsEntry("go.mod", "content of go.mod");
    }

    @Test
    void aFileTreeLargerThanTheDefaultBufferIsStillRead() throws Exception {
        // Close to 1 MB of JSON: several times the 256 KB default that made large repositories fail.
        StringBuilder json = new StringBuilder("{\"tree\":[");
        int entries = 12_000;
        for (int i = 0; i < entries; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"path\":\"src/main/java/com/example/some/package/File").append(i)
                    .append(".java\",\"type\":\"blob\",\"size\":10}");
        }
        byte[] body = json.append("],\"truncated\":false}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body.length).isGreaterThan(3 * 256 * 1024);

        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            // The real client configuration, pointed at the local server instead of api.github.com.
            com.binocheck.config.WebClientConfig config = new com.binocheck.config.WebClientConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(config, "githubToken", "");
            org.springframework.test.util.ReflectionTestUtils.setField(config, "connectTimeout", Duration.ofSeconds(5));
            org.springframework.test.util.ReflectionTestUtils.setField(config, "githubResponseTimeout", Duration.ofSeconds(15));
            WebClient client = config.githubWebClient(WebClient.builder()).mutate()
                    .baseUrl("http://localhost:" + server.getAddress().getPort()).build();

            assertThat(new GitHubService(client, new ObjectMapper()).fetchTree("o", "r", "main")).hasSize(entries);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anEmptyRepositoryGetsAClearMessageInsteadOfGitHubCouldNotBeReached() {
        ExchangeFunction github = request -> Mono.just(
                ClientResponse.create(HttpStatus.CONFLICT).body("{\"message\":\"Git Repository is empty.\"}").build());

        assertThatThrownBy(() -> serviceBackedBy(github).fetchTree("o", "r", "main"))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("empty");
                });
    }

    @Test
    void aHugeReadmeIsCutDownBeforeItReachesThePrompt() {
        String capped = GitHubService.capReadme("x".repeat(GitHubService.MAX_README_CHARS + 5_000));

        assertThat(capped).hasSizeLessThan(GitHubService.MAX_README_CHARS + 100).endsWith("[README truncated]");
        assertThat(GitHubService.capReadme("short")).isEqualTo("short");
        assertThat(GitHubService.capReadme(null)).isEmpty();
    }

    @Test
    void anUnreachableGitHubIsReportedAsBadGatewayWithoutInternalDetails() {
        ExchangeFunction github = request -> Mono.error(new java.io.IOException("connect timed out to 10.0.0.1"));

        assertThatThrownBy(() -> serviceBackedBy(github).fetchMetadata("o", "r"))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).doesNotContain("10.0.0.1");
                });
    }
}
