package com.misho.exchange.ratelimit;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Bounded, process-local token buckets. No credentials or request bodies are retained. */
@Component
public class RequestLimits {
    public enum Policy { LOGIN(10, 60), RECOVERY(5, 3600), REGISTRATION(5, 3600), TRADING(60, 60), EXTERNAL(180, 60), READS(600, 60);
        final int capacity;
        final int seconds;
        Policy(int capacity, int seconds) { this.capacity = capacity; this.seconds = seconds; }
    }
    private record Key(Policy policy, String identity) { }
    private static final class Bucket {
        double tokens;
        long updated;
        Bucket(double tokens, long updated) { this.tokens = tokens; this.updated = updated; }
    }
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private final Map<Policy, Integer> capacities = new java.util.EnumMap<>(Policy.class);
    private final LongSupplier clock;
    private final int maxKeys;
    @org.springframework.beans.factory.annotation.Autowired
    public RequestLimits(Environment env) { this(env, System::nanoTime); }
    RequestLimits(Environment env, LongSupplier clock) {
        this.clock = clock;
        maxKeys = env.getProperty("verdex.rate-limit.max-keys", Integer.class, 10000);
        if (maxKeys < 1) throw new IllegalArgumentException("Rate limit max-keys must be positive");
        for (Policy policy : Policy.values()) {
            int capacity = env.getProperty("verdex.rate-limit." + policy.name().toLowerCase(java.util.Locale.ROOT), Integer.class, policy.capacity);
            if (capacity < 1) throw new IllegalArgumentException("Rate limit capacities must be positive");
            capacities.put(policy, capacity);
        }
    }
    /** Returns zero when accepted, otherwise whole seconds until a token is available. */
    public synchronized long acquire(Policy policy, String identity) {
        long now = clock.getAsLong();
        int capacity = capacities.get(policy);
        Key key = new Key(policy, identity);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                buckets.entrySet().removeIf(entry -> now - entry.getValue().updated >= entry.getKey().policy.seconds * 1_000_000_000L);
                if (buckets.size() >= maxKeys) return 60; // Do not evict active limits to admit arbitrary identities.
            }
            bucket = new Bucket(capacity, now);
            buckets.put(key, bucket);
        }
        double rate = capacity / (policy.seconds * 1_000_000_000.0);
        bucket.tokens = Math.min(capacity, bucket.tokens + Math.max(0, now - bucket.updated) * rate);
        bucket.updated = now;
        if (bucket.tokens < 1) return Math.max(1, (long) Math.ceil((1 - bucket.tokens) / rate / 1_000_000_000.0));
        bucket.tokens -= 1;
        return 0;
    }
}
