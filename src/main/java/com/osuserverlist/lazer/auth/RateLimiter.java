package com.osuserverlist.lazer.auth;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter for failed attempts (logins, codes). Keys are things like "ip:1.2.3.4" or "user:name".
 * In memory only: it resets on restart, which is fine for slowing down guessing.
 */
public final class RateLimiter {
    private final int maxFailures;
    private final long windowMs;
    private final Map<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();

    public RateLimiter(int maxFailures, long windowMs) {
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
    }

    /** True while the key has used up its failures inside the window. */
    public boolean isBlocked(String key) {
        ArrayDeque<Long> q = failures.get(key);
        if (q == null) return false;
        synchronized (q) {
            prune(q, System.currentTimeMillis());
            return q.size() >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        if (failures.size() > 100_000) {
            long now = System.currentTimeMillis();
            failures.values().removeIf(q -> {
                synchronized (q) {
                    prune(q, now);
                    return q.isEmpty();
                }
            });
        }
        ArrayDeque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
            prune(q, System.currentTimeMillis());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }
    }
}
