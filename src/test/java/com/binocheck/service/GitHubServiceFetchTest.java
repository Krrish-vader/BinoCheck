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
    void anUnreachableGitHubIsReportedAsBadGatewayWithoutInternalDetails() {
        ExchangeFunction github = request -> Mono.error(new java.io.IOException("connect timed out to 10.0.0.1"));

        assertThatThrownBy(() -> serviceBackedBy(github).fetchMetadata("o", "r"))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).doesNotContain("10.0.0.1");
                });
    }
}
