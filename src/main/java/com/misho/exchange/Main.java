package com.misho.exchange;

import java.math.BigDecimal;
import java.util.List;

public final class Main {

    private Main() { }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 4) {
            throw new IllegalArgumentException("Usage: Main [durationSeconds] [intervalMillis] [seed] [selectedSymbol]");
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

        boolean interactiveTerminal = System.console() != null && !"dumb".equals(System.getenv("TERM"));
        String clearOverride = System.getProperty("dashboard.clear");
        boolean clearScreen = clearOverride == null ? interactiveTerminal : Boolean.parseBoolean(clearOverride);
        boolean useColor = clearScreen && System.getenv("NO_COLOR") == null
                && !"false".equalsIgnoreCase(System.getProperty("dashboard.color"));
        String selectedSymbol = args.length > 3 ? args[3] : "AAPL";
        ConsoleDashboard dashboard = new ConsoleDashboard(
                exchange, symbols, System.out, clearScreen, useColor, selectedSymbol);
        Thread terminalCleanup = new Thread(dashboard::close, "dashboard-terminal-cleanup");
        Runtime.getRuntime().addShutdownHook(terminalCleanup);
        try (dashboard; MarketSimulator simulator = new MarketSimulator(exchange, symbols, intervalMillis, 10, 50, seed)) {
            long started = System.nanoTime();
            simulator.start();
            while (true) {
                if (simulator.getLastFailure() != null) {
                    throw new IllegalStateException("Simulation failed.", simulator.getLastFailure());
                }
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                if (elapsedMillis >= durationMillis) {
                    break;
                }
                dashboard.refresh(elapsedMillis / 1_000, simulator.getSubmittedOrderCount(), true);
                Thread.sleep(Math.min(1_000, durationMillis - elapsedMillis));
            }
            simulator.stop();
            if (simulator.getLastFailure() != null) {
                throw new IllegalStateException("Simulation failed.", simulator.getLastFailure());
            }
            dashboard.refresh((System.nanoTime() - started) / 1_000_000_000,
                    simulator.getSubmittedOrderCount(), false);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(terminalCleanup);
            } catch (IllegalStateException ignored) {
            }
        }
    }
}
