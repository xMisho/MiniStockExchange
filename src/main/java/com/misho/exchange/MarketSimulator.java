package com.misho.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MarketSimulator implements AutoCloseable {

    public record SimulationStep(String symbol, long orderId, OrderSide side,
                                 OrderType orderType, BigDecimal limitPrice, int quantity) { }

    private final Exchange exchange;
    private final List<String> symbols;
    private final long intervalMillis;
    private final int maxQuantity;
    private final int maxOpenOrdersPerStock;
    private final Random random;
    private final Map<String, Deque<Long>> ownOrders = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private boolean running;
    private boolean closed;
    private long generation;
    private long submittedOrderCount;
    private RuntimeException lastFailure;

    public MarketSimulator(Exchange exchange, List<String> symbols, long intervalMillis,
                           int maxQuantity, int maxOpenOrdersPerStock, long seed) {
        if (exchange == null || symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("Exchange and at least one registered stock are required.");
        }
        if (intervalMillis <= 0 || maxQuantity <= 0 || maxOpenOrdersPerStock <= 0) {
            throw new IllegalArgumentException("Interval, maximum quantity, and open-order limit must be positive.");
        }
        List<String> normalized = new ArrayList<>();
        for (String symbol : symbols) {
            Stock stock = exchange.getStock(symbol);
            if (stock == null) {
                throw new IllegalArgumentException("Stock is not registered: " + symbol);
            }
            String canonical = stock.getSymbol();
            if (normalized.contains(canonical)) {
                throw new IllegalArgumentException("Duplicate stock symbol: " + canonical);
            }
            normalized.add(canonical);
            ownOrders.put(canonical, new ArrayDeque<>());
        }
        this.exchange = exchange;
        this.symbols = List.copyOf(normalized);
        this.intervalMillis = intervalMillis;
        this.maxQuantity = maxQuantity;
        this.maxOpenOrdersPerStock = maxOpenOrdersPerStock;
        this.random = new Random(seed);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread worker = new Thread(runnable, "market-simulator");
            worker.setDaemon(true);
            return worker;
        });
    }

    public synchronized SimulationStep step() {
        ensureOpen();
        String symbol = symbols.get(random.nextInt(symbols.size()));
        OrderSide side = random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
        OrderType type = random.nextInt(4) == 0 ? OrderType.MARKET : OrderType.LIMIT;
        int quantity = random.nextInt(maxQuantity) + 1;
        BigDecimal price = null;
        if (type == OrderType.LIMIT) {
            BigDecimal anchor = exchange.getLastTradedPrice(symbol);
            if (anchor == null) {
                anchor = exchange.getStock(symbol).getReferencePrice();
            }
            int offsetBasisPoints = random.nextInt(201) - 100;
            price = anchor.multiply(BigDecimal.valueOf(10_000L + offsetBasisPoints, 4))
                    .setScale(Math.max(2, anchor.scale()), RoundingMode.HALF_UP);
        }

        Deque<Long> outstanding = ownOrders.get(symbol);
        outstanding.removeIf(id -> exchange.getOpenOrder(symbol, id) == null);
        if (type == OrderType.LIMIT && outstanding.size() >= maxOpenOrdersPerStock) {
            exchange.cancelOrder(symbol, outstanding.removeFirst());
        }
        Order order = exchange.submitGeneratedOrder(symbol, side, type, price, quantity);
        submittedOrderCount++;
        if (exchange.getOpenOrder(symbol, order.getOrderId()) != null) {
            outstanding.addLast(order.getOrderId());
        }
        return new SimulationStep(symbol, order.getOrderId(), side, type, price, quantity);
    }

    public synchronized void start() {
        ensureOpen();
        if (running) {
            return;
        }
        lastFailure = null;
        running = true;
        long currentGeneration = ++generation;
        task = scheduler.scheduleWithFixedDelay(
                () -> runScheduledStep(currentGeneration), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        generation++;
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private synchronized void runScheduledStep(long currentGeneration) {
        if (!running || closed || generation != currentGeneration) {
            return;
        }
        try {
            step();
        } catch (RuntimeException failure) {
            lastFailure = failure;
            stop();
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized long getSubmittedOrderCount() {
        return submittedOrderCount;
    }

    public synchronized RuntimeException getLastFailure() {
        return lastFailure;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            stop();
            closed = true;
            scheduler.shutdown();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Simulator is closed.");
        }
    }
}
