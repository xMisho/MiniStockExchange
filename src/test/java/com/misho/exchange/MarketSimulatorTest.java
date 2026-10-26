package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class MarketSimulatorTest {

    @Test
    void validatesConfigurationBeforeStartingThreads() {
        Exchange exchange = exchange();
        assertThrows(IllegalArgumentException.class, () -> simulator(null, List.of("AAPL"), 10, 10, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, null, 10, 10, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, List.of(), 10, 10, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, List.of("UNKNOWN"), 10, 10, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, List.of("AAPL", " aapl "), 10, 10, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, List.of("AAPL"), 0, 10, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, List.of("AAPL"), 10, 0, 3, 42));
        assertThrows(IllegalArgumentException.class, () -> simulator(exchange, List.of("AAPL"), 10, 10, 0, 42));
        try (MarketSimulator simulator = simulator(exchange, List.of(" aapl "), 10, 10, 3, 42)) {
            assertEquals("AAPL", simulator.step().symbol());
        }
    }

    @Test
    void identicalSeedsAndInitialBooksProduceIdenticalManualRuns() {
        Exchange first = exchange();
        Exchange second = exchange();
        try (MarketSimulator a = simulator(first, List.of("AAPL", "TSLA"), 10, 10, 20, 42);
             MarketSimulator b = simulator(second, List.of("AAPL", "TSLA"), 10, 10, 20, 42)) {
            for (int i = 0; i < 500; i++) {
                assertEquals(a.step(), b.step());
            }
            for (String symbol : List.of("AAPL", "TSLA")) {
                assertEquals(first.getBuyDepth(symbol), second.getBuyDepth(symbol));
                assertEquals(first.getSellDepth(symbol), second.getSellDepth(symbol));
                assertEquals(first.getTradeCount(symbol), second.getTradeCount(symbol));
                assertEquals(first.getVwap(symbol), second.getVwap(symbol));
            }
            assertEquals(500, a.getSubmittedOrderCount());
        }
    }

    @Test
    void differentSeedsProduceDifferentOrderSequences() {
        try (MarketSimulator a = simulator(exchange(), List.of("AAPL"), 10, 10, 10, 1);
             MarketSimulator b = simulator(exchange(), List.of("AAPL"), 10, 10, 10, 2)) {
            List<MarketSimulator.SimulationStep> left = new ArrayList<>();
            List<MarketSimulator.SimulationStep> right = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                left.add(a.step());
                right.add(b.step());
            }
            assertNotEquals(left, right);
        }
    }

    @Test
    void generatesBothSidesAndTypesWithValidQuantitiesAndNearbyLimitPrices() {
        Exchange exchange = exchange();
        Set<OrderSide> sides = new HashSet<>();
        Set<OrderType> types = new HashSet<>();
        try (MarketSimulator simulator = simulator(exchange, List.of("AAPL"), 10, 10, 20, 42)) {
            for (int i = 0; i < 300; i++) {
                BigDecimal anchor = exchange.getLastTradedPrice("AAPL");
                if (anchor == null) {
                    anchor = exchange.getStock("AAPL").getReferencePrice();
                }
                MarketSimulator.SimulationStep step = simulator.step();
                sides.add(step.side());
                types.add(step.orderType());
                assertTrue(step.quantity() >= 1 && step.quantity() <= 10);
                assertTrue(step.orderId() > 0);
                if (step.orderType() == OrderType.MARKET) {
                    assertNull(step.limitPrice());
                    assertNull(exchange.getOpenOrder("AAPL", step.orderId()));
                } else {
                    assertTrue(step.limitPrice().signum() > 0);
                    assertTrue(step.limitPrice().subtract(anchor).abs().compareTo(
                            anchor.multiply(new BigDecimal("0.01")).add(new BigDecimal("0.01"))) <= 0);
                }
            }
        }
        assertEquals(Set.of(OrderSide.BUY, OrderSide.SELL), sides);
        assertEquals(Set.of(OrderType.LIMIT, OrderType.MARKET), types);
    }

    @Test
    void boundsItsOwnOpenOrdersWithoutCancellingManualOrders() {
        Exchange exchange = exchange();
        Order manual = new Order(1, OrderSide.SELL, new BigDecimal("1000000"), Integer.MAX_VALUE);
        exchange.submitOrder("AAPL", manual);
        try (MarketSimulator simulator = simulator(exchange, List.of("AAPL"), 10, 10, 3, 42)) {
            for (int i = 0; i < 300; i++) {
                simulator.step();
                assertTrue(exchange.getOpenOrderCount("AAPL") <= 4);
                assertFalse(manual.isCancelled());
            }
            assertSame(manual, exchange.getOpenOrder("AAPL", 1));
        }
    }

    @Test
    void generatedIdsSkipFilledAndCancelledManualIdsAndContinueAcrossStops() {
        Exchange exchange = exchange();
        exchange.submitOrder("AAPL", new Order(1, OrderSide.SELL, new BigDecimal("100"), 1));
        exchange.submitOrder("AAPL", new Order(2, OrderSide.BUY, new BigDecimal("100"), 1));
        exchange.submitOrder("AAPL", new Order(3, OrderSide.BUY, new BigDecimal("50"), 1));
        exchange.cancelOrder("AAPL", 3);
        Set<Long> ids = new HashSet<>();
        try (MarketSimulator simulator = simulator(exchange, List.of("AAPL"), 10, 10, 3, 42)) {
            for (int i = 0; i < 200; i++) {
                MarketSimulator.SimulationStep step = simulator.step();
                assertTrue(step.orderId() > 3);
                assertTrue(ids.add(step.orderId()));
                if (i == 100) {
                    simulator.stop();
                }
            }
        }
        assertEquals(200, ids.size());
    }

    @Test
    void separateSimulatorsShareCollisionFreeEngineIdsUnderConcurrentSteps() throws Exception {
        Exchange exchange = exchange();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (MarketSimulator a = simulator(exchange, List.of("AAPL"), 10, 10, 5, 1);
             MarketSimulator b = simulator(exchange, List.of("AAPL"), 10, 10, 5, 2)) {
            Future<List<Long>> first = workers.submit(() -> ids(a, 100));
            Future<List<Long>> second = workers.submit(() -> ids(b, 100));
            Set<Long> unique = new HashSet<>(first.get(5, TimeUnit.SECONDS));
            assertEquals(100, unique.size());
            for (long id : second.get(5, TimeUnit.SECONDS)) {
                assertTrue(unique.add(id));
            }
            assertEquals(200, unique.size());
            assertEquals(100, a.getSubmittedOrderCount());
            assertEquals(100, b.getSubmittedOrderCount());
            assertTrue(exchange.getOpenOrderCount("AAPL") <= 10);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void tinyPositiveReferencePricesStillGeneratePositiveLimitPrices() {
        Exchange exchange = new Exchange();
        exchange.registerStock(new Stock("TINY", "Tiny", new BigDecimal("0.0001")));
        try (MarketSimulator simulator = simulator(exchange, List.of("TINY"), 10, 5, 5, 42)) {
            for (int i = 0; i < 100; i++) {
                MarketSimulator.SimulationStep step = simulator.step();
                if (step.orderType() == OrderType.LIMIT) {
                    assertTrue(step.limitPrice().signum() > 0);
                }
            }
        }
    }

    @Test
    void scheduledGenerationCanBeStoppedAndRestarted() throws Exception {
        ObservedExchange exchange = observedExchange();
        try (MarketSimulator simulator = simulator(exchange, List.of("AAPL"), 5, 10, 5, 42)) {
            simulator.start();
            simulator.start();
            Long firstId = exchange.submissions.poll(5, TimeUnit.SECONDS);
            assertNotNull(firstId);
            simulator.stop();
            assertFalse(simulator.isRunning());
            long count = simulator.getSubmittedOrderCount();
            exchange.submissions.clear();
            assertNull(exchange.submissions.poll(30, TimeUnit.MILLISECONDS));
            assertEquals(count, simulator.getSubmittedOrderCount());
            simulator.start();
            Long nextId = exchange.submissions.poll(5, TimeUnit.SECONDS);
            assertNotNull(nextId);
            simulator.stop();
            assertTrue(nextId > firstId);
            assertTrue(simulator.getSubmittedOrderCount() > count);
            assertNull(simulator.getLastFailure());
        }
    }

    @Test
    void closeIsIdempotentAndPreventsFurtherManualOrScheduledSteps() {
        Exchange exchange = exchange();
        MarketSimulator simulator = simulator(exchange, List.of("AAPL"), 60_000, 10, 5, 42);
        try {
            simulator.step();
            simulator.start();
        } finally {
            simulator.close();
        }
        simulator.close();
        assertFalse(simulator.isRunning());
        assertEquals(1, simulator.getSubmittedOrderCount());
        assertThrows(IllegalStateException.class, simulator::start);
        assertThrows(IllegalStateException.class, simulator::step);
    }

    @Test
    void scheduledFailuresStopTheRunAndRemainVisibleUntilRestart() throws Exception {
        ObservedExchange exchange = observedExchange();
        exchange.failNext.set(true);
        try (MarketSimulator simulator = simulator(exchange, List.of("AAPL"), 5, 10, 5, 42)) {
            simulator.start();
            assertTrue(exchange.failed.await(5, TimeUnit.SECONDS));
            simulator.stop();
            assertSame(exchange.failure, simulator.getLastFailure());
            assertFalse(simulator.isRunning());
            assertEquals(0, simulator.getSubmittedOrderCount());
            simulator.start();
            assertNotNull(exchange.submissions.poll(5, TimeUnit.SECONDS));
            simulator.stop();
            assertNull(simulator.getLastFailure());
        }
    }

    @Test
    void seededActivityProducesRealTradesAndUpdatesRegisteredStocks() {
        Exchange exchange = exchange();
        try (MarketSimulator simulator = simulator(exchange, List.of("AAPL", "TSLA"), 10, 10, 20, 42)) {
            for (int i = 0; i < 500; i++) {
                simulator.step();
            }
            for (String symbol : List.of("AAPL", "TSLA")) {
                assertTrue(exchange.getTradeCount(symbol) > 0);
                assertTrue(exchange.getTotalTradedVolume(symbol) > 0);
                assertNotNull(exchange.getLastTradedPrice(symbol));
                assertNotNull(exchange.getVwap(symbol));
                assertNotNull(exchange.getLatestOhlcCandle(symbol));
                assertNotNull(exchange.getPriceChangePercent(symbol));
                assertTrue(exchange.getOpenOrderCount(symbol) <= 20);
            }
        }
    }

    private static List<Long> ids(MarketSimulator simulator, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(simulator.step().orderId());
        }
        return ids;
    }

    private static MarketSimulator simulator(Exchange exchange, List<String> symbols,
                                              long interval, int quantity, int open, long seed) {
        return new MarketSimulator(exchange, symbols, interval, quantity, open, seed);
    }

    private static Exchange exchange() {
        Exchange exchange = new Exchange();
        register(exchange);
        return exchange;
    }

    private static ObservedExchange observedExchange() {
        ObservedExchange exchange = new ObservedExchange();
        register(exchange);
        return exchange;
    }

    private static void register(Exchange exchange) {
        exchange.registerStock(new Stock("AAPL", "Apple Inc.", new BigDecimal("250.00")));
        exchange.registerStock(new Stock("TSLA", "Tesla Inc.", new BigDecimal("200.00")));
    }

    private static final class ObservedExchange extends Exchange {
        final BlockingQueue<Long> submissions = new LinkedBlockingQueue<>();
        final AtomicBoolean failNext = new AtomicBoolean();
        final CountDownLatch failed = new CountDownLatch(1);
        final RuntimeException failure = new IllegalStateException("Controlled test failure");

        @Override
        Order submitGeneratedOrder(String symbol, OrderSide side, OrderType type,
                                   BigDecimal price, int quantity) {
            if (failNext.getAndSet(false)) {
                failed.countDown();
                throw failure;
            }
            Order order = super.submitGeneratedOrder(symbol, side, type, price, quantity);
            submissions.add(order.getOrderId());
            return order;
        }
    }
}
