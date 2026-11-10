package com.binocheck.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.StringUtils;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Value("${app.github.token:}")
    private String githubToken;

    @Value("${app.gemini.key:}")
    private String geminiKey;

    @Value("${app.http.connect-timeout:5s}")
    private Duration connectTimeout;

    @Value("${app.github.response-timeout:15s}")
    private Duration githubResponseTimeout;

    // Analysing a large repository can legitimately take a while, so Gemini gets a longer limit.
    @Value("${app.gemini.response-timeout:90s}")
    private Duration geminiResponseTimeout;

    /**
     * Every call ends in {@code .block()} on a Tomcat thread, so an upstream that never answers would
     * hold that thread forever. Both timeouts bound how long a request can wait.
     */
    public static WebClient.Builder withTimeouts(WebClient.Builder builder, Duration connectTimeout, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);
        return builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    @Bean
    public WebClient githubWebClient(WebClient.Builder builder) {
        WebClient.Builder clientBuilder = withTimeouts(builder, connectTimeout, githubResponseTimeout)
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
        WebClient.Builder clientBuilder = withTimeouts(builder, connectTimeout, geminiResponseTimeout)
                .baseUrl("https://generativelanguage.googleapis.com");

        if (StringUtils.hasText(geminiKey)) {
            clientBuilder.defaultHeader("x-goog-api-key", geminiKey.trim());
        }

        // Increase buffer size to handle larger response payloads
        clientBuilder.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)); // 10MB

        return clientBuilder.build();
    }
}
