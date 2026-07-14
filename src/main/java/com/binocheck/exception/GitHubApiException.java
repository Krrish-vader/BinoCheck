package com.binocheck.exception;

import org.springframework.http.HttpStatus;

public class GitHubApiException extends RuntimeException {
    private final HttpStatus status;

    public GitHubApiException(String message) {
        super(message);
        this.status = HttpStatus.BAD_GATEWAY;
    }

    public GitHubApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
