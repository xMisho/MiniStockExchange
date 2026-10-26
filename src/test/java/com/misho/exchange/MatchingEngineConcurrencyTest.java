package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class MatchingEngineConcurrencyTest {

    @Test
    void concurrentDuplicateSubmissionsAcceptExactlyOneOrder() throws Exception {
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> submit = () -> {
                ready.countDown();
                start.await();
                try {
                    engine.submitOrder(new Order(9901, OrderSide.BUY, new BigDecimal("100"), 5));
                    return true;
                } catch (IllegalArgumentException error) {
                    assertEquals("Order ID has already been used.", error.getMessage());
                    return false;
                }
            };
            Future<Boolean> first = workers.submit(submit);
            Future<Boolean> second = workers.submit(submit);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, accepted);
            assertEquals(1, engine.getOpenOrderCount());
            assertEquals(5, engine.getOpenOrder(9901).getRemainingQuantity());
            assertEquals(0, engine.getTradeCount());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void validationExceptionsDoNotBlockAnotherThreadsSubmission() throws Exception {
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(null));
        engine.submitOrder(new Order(9902, OrderSide.SELL, new BigDecimal("100"), 5));
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(
                new Order(9902, OrderSide.BUY, new BigDecimal("100"), 5)));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> submitted = worker.submit(() -> engine.submitOrder(
                    new Order(9903, OrderSide.BUY, new BigDecimal("100"), 5)));
            submitted.get(5, TimeUnit.SECONDS);
            assertEquals(1, engine.getTradeCount());
            assertEquals(5, engine.getTotalTradedVolume());
            assertEquals(0, engine.getOpenOrderCount());
            assertEquals(new BigDecimal("100.0000"), engine.getVwap());
        } finally {
            worker.shutdownNow();
        }
    }
}
