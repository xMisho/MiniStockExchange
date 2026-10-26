package com.misho.exchange;

import java.math.BigDecimal;
import java.util.List;

public final class Main {

    private Main() { }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 3) {
            throw new IllegalArgumentException("Usage: Main [durationSeconds] [intervalMillis] [seed]");
        }
        long durationSeconds = args.length > 0 ? Long.parseLong(args[0]) : 10;
        long intervalMillis = args.length > 1 ? Long.parseLong(args[1]) : 100;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42;
        if (durationSeconds <= 0 || intervalMillis <= 0) {
            throw new IllegalArgumentException("Duration and interval must be positive.");
        }
        long durationMillis = Math.multiplyExact(durationSeconds, 1_000);
        Exchange exchange = new Exchange();
        exchange.registerStock(new Stock("AAPL", "Apple Inc.", new BigDecimal("250.00")));
        exchange.registerStock(new Stock("TSLA", "Tesla Inc.", new BigDecimal("200.00")));
        List<String> symbols = List.of("AAPL", "TSLA");

        long submitted;
        try (MarketSimulator simulator = new MarketSimulator(exchange, symbols, intervalMillis, 10, 50, seed)) {
            System.out.println("Simulating AAPL and TSLA for " + durationSeconds + " seconds...");
            simulator.start();
            Thread.sleep(durationMillis);
            simulator.stop();
            if (simulator.getLastFailure() != null) {
                throw new IllegalStateException("Simulation failed.", simulator.getLastFailure());
            }
            submitted = simulator.getSubmittedOrderCount();
        }
        System.out.println("Submitted orders: " + submitted);
        for (String symbol : symbols) {
            System.out.println(symbol + " | trades=" + exchange.getTradeCount(symbol)
                    + " | volume=" + exchange.getTotalTradedVolume(symbol)
                    + " | last=" + exchange.getLastTradedPrice(symbol)
                    + " | open=" + exchange.getOpenOrderCount(symbol)
                    + " | VWAP=" + exchange.getVwap(symbol));
        }
    }
}
