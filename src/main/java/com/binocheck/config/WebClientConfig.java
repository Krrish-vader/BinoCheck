package com.binocheck.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.StringUtils;

@Configuration
public class WebClientConfig {

    @Value("${app.github.token:}")
    private String githubToken;

    @Value("${app.gemini.key:}")
    private String geminiKey;

    @Bean
    public WebClient githubWebClient(WebClient.Builder builder) {
        WebClient.Builder clientBuilder = builder
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        if (StringUtils.hasText(githubToken)) {
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + githubToken.trim());
        }

        return clientBuilder.build();
    }

    @Bean
    public WebClient geminiWebClient(WebClient.Builder builder) {
        WebClient.Builder clientBuilder = builder
                .baseUrl("https://generativelanguage.googleapis.com");

        if (StringUtils.hasText(geminiKey)) {
            clientBuilder.defaultHeader("x-goog-api-key", geminiKey.trim());
        }

        // Increase buffer size to handle larger response payloads
        clientBuilder.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)); // 10MB

        return clientBuilder.build();
    }
}
