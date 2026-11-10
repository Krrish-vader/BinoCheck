package com.binocheck.service;

import com.binocheck.dto.RepoCoordinates;
import com.binocheck.exception.InvalidGitHubUrlException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubServiceUrlTest {

    private final GitHubService service = new GitHubService(WebClient.create(), new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/Krrish-vader/BinoCheck",
            "https://github.com/Krrish-vader/BinoCheck/",
            "https://github.com/Krrish-vader/BinoCheck.git",
            "http://www.github.com/Krrish-vader/BinoCheck",
            "github.com/Krrish-vader/BinoCheck",
            "https://github.com/Krrish-vader/BinoCheck/tree/main/src",
            "  https://github.com/Krrish-vader/BinoCheck  "
    })
    void acceptsRepositoryUrlsAndNormalisesThem(String url) {
        RepoCoordinates coords = service.parseUrl(url);

        assertThat(coords.getOwner()).isEqualTo("Krrish-vader");
        assertThat(coords.getRepo()).isEqualTo("BinoCheck");
        assertThat(service.canonicalUrl(coords)).isEqualTo("https://github.com/krrish-vader/binocheck");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not a url",
            "https://github.com/onlyowner",
            "https://evil.example/github.com/a/b",
            "javascript:alert(1)//github.com/a/b",
            "https://github.com/<img src=x onerror=alert(1)>/x",
            "https://github.com/a/..",
            "https://gitlab.com/a/b"
    })
    void rejectsAnythingThatIsNotAGitHubRepositoryUrl(String url) {
        assertThatThrownBy(() -> service.parseUrl(url)).isInstanceOf(InvalidGitHubUrlException.class);
    }
}
