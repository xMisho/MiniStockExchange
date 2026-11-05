package com.misho.exchange.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequestLimitsTest {
    @Test void policiesAndIdentitiesAreIndependentAndRefillWithoutSleeping() {
        AtomicLong time = new AtomicLong();
        var limits = new RequestLimits(new MockEnvironment(), time::get);
        for (var policy : RequestLimits.Policy.values()) {
            for (int i = 0; i < policy.capacity; i++) assertEquals(0, limits.acquire(policy, "one"));
            assertTrue(limits.acquire(policy, "one") > 0);
            assertEquals(0, limits.acquire(policy, "two"));
        }
        time.set(3600_000_000_000L);
        for (var policy : RequestLimits.Policy.values()) assertEquals(0, limits.acquire(policy, "one"));
    }
    @Test void memoryIsBoundedAndActiveLimitsCannotBeEvictedByNewIdentities() {
        AtomicLong time = new AtomicLong();
        var limits = new RequestLimits(new MockEnvironment().withProperty("verdex.rate-limit.max-keys", "1")
                .withProperty("verdex.rate-limit.login", "1"), time::get);
        assertEquals(0, limits.acquire(RequestLimits.Policy.LOGIN, "one"));
        assertTrue(limits.acquire(RequestLimits.Policy.LOGIN, "two") > 0);
        assertTrue(limits.acquire(RequestLimits.Policy.LOGIN, "one") > 0);
        time.set(60_000_000_000L);
        assertEquals(0, limits.acquire(RequestLimits.Policy.LOGIN, "two"));
    }
    @Test void concurrentRequestsCannotExceedCapacity() throws Exception {
        var limits = new RequestLimits(new MockEnvironment(), () -> 0);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 100; i++) calls.add(() -> limits.acquire(RequestLimits.Policy.LOGIN, "one") == 0);
            long accepted = 0;
            for (var future : pool.invokeAll(calls)) if (future.get()) accepted++;
            assertEquals(10, accepted);
        } finally { pool.shutdownNow(); }
    }
}
