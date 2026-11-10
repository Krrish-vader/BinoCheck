package com.binocheck.security;

import com.binocheck.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final AtomicLong nowMillis = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(nowMillis.get()); }
    };
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void allowsUpToTheLimitThenRejectsWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            limiter.check("k", 3, Duration.ofMinutes(1));
        }

        assertThatThrownBy(() -> limiter.check("k", 3, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void keysAreIndependent() {
        limiter.check("a", 1, Duration.ofMinutes(1));

        assertThatCode(() -> limiter.check("b", 1, Duration.ofMinutes(1))).doesNotThrowAnyException();
    }

    @Test
    void windowResetsAfterItExpires() {
        limiter.check("k", 1, Duration.ofMinutes(1));
        nowMillis.addAndGet(Duration.ofMinutes(1).toMillis());

        assertThatCode(() -> limiter.check("k", 1, Duration.ofMinutes(1))).doesNotThrowAnyException();
    }
}
