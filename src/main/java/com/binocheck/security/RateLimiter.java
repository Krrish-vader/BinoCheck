package com.binocheck.security;

import com.binocheck.exception.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory fixed-window rate limiter. Good enough for a single instance;
 * use Redis/Bucket4j if the app is ever scaled to several instances.
 */
@Component
public class RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one request for {@code key}; throws {@link RateLimitExceededException}
     * if more than {@code limit} requests were made in the current {@code window}.
     */
    public void check(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        }
        Window w = windows.compute(key, (k, existing) ->
                existing == null || existing.expiresAt <= now
                        ? new Window(now + window.toMillis(), 1)
                        : new Window(existing.expiresAt, existing.count + 1));
        if (w.count > limit) {
            long retryAfterSeconds = Math.max(1, (w.expiresAt - now + 999) / 1000);
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    private record Window(long expiresAt, int count) {}
}
