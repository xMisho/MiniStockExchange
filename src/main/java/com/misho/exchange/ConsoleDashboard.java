package com.misho.exchange;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ConsoleDashboard implements AutoCloseable {

    private final Exchange exchange;
    private final List<String> symbols;
    private final PrintStream output;
    private final boolean clearScreen;
    private final boolean useColor;
    private final String selectedSymbol;
    private boolean alternateScreen;
    private boolean closed;

    public ConsoleDashboard(Exchange exchange, List<String> symbols,
                            PrintStream output, boolean clearScreen) {
        this(exchange, symbols, output, clearScreen, false, null);
    }

    public ConsoleDashboard(Exchange exchange, List<String> symbols,
                            PrintStream output, boolean clearScreen,
                            boolean useColor, String selectedSymbol) {
        if (exchange == null || symbols == null || symbols.isEmpty() || output == null) {
            throw new IllegalArgumentException("Exchange, stock symbols, and output are required.");
        }
        this.symbols = symbols.stream().map(symbol -> {
            Stock stock = exchange.getStock(symbol);
            if (stock == null) {
                throw new IllegalArgumentException("Stock is not registered: " + symbol);
            }
            return stock.getSymbol();
        }).toList();
        this.exchange = exchange;
        this.output = output;
        this.clearScreen = clearScreen;
        this.useColor = useColor;
        Stock selected = selectedSymbol == null ? exchange.getStock(this.symbols.get(0))
                : exchange.getStock(selectedSymbol);
        if (selected == null || !this.symbols.contains(selected.getSymbol())) {
            throw new IllegalArgumentException("Selected stock must be in the dashboard.");
        }
        this.selectedSymbol = selected.getSymbol();
    }

    public String render(long elapsedSeconds, long submittedOrders, boolean running) {
        StringBuilder screen = new StringBuilder(heading("MINI STOCK EXCHANGE | LIVE MARKET") + "\n");
        screen.append(String.format(Locale.ROOT, "%s | Elapsed: %ds | Submitted orders: %d%n%n",
                running ? "RUNNING" : "COMPLETED", elapsedSeconds, submittedOrders));
        String format = "%-8s %10s %10s %10s %10s %10s %10s %8s %8s %12s%n";
        screen.append(String.format(Locale.ROOT, format,
                "SYMBOL", "LAST", "CHANGE", "CHANGE %", "BID", "ASK", "VOLUME", "TRADES", "OPEN", "VWAP"));
        MarketSnapshot selected = null;
        for (String symbol : symbols) {
            MarketSnapshot snapshot = exchange.getMarketSnapshot(symbol);
            if (symbol.equals(selectedSymbol)) {
                selected = snapshot;
            }
            String rowFormat = "%-8s %10s %s %s %10s %10s %10s %8s %8s %12s%n";
            screen.append(String.format(Locale.ROOT, rowFormat,
                    snapshot.symbol(), decimal(snapshot.lastTradedPrice(), 2, false),
                    changeCell(snapshot.priceChange(), false),
                    changeCell(snapshot.priceChangePercent(), true),
                    decimal(snapshot.bestBidPrice(), 2, false),
                    decimal(snapshot.bestAskPrice(), 2, false),
                    snapshot.volume(), snapshot.tradeCount(), snapshot.openOrderCount(),
                    decimal(snapshot.vwap(), 2, false)));
        }
        appendDetails(screen, selected);
        screen.append("\nLocal simulation | Prices shown to 2 decimals | Volume in shares\n");
        return screen.toString();
    }

    public synchronized void refresh(long elapsedSeconds, long submittedOrders, boolean running) {
        if (closed) {
            throw new IllegalStateException("Dashboard is closed.");
        }
        String screen = render(elapsedSeconds, submittedOrders, running);
        if (clearScreen && running) {
            if (!alternateScreen) {
                output.print("\033[?1049h\033[?25l");
                alternateScreen = true;
            }
            output.print("\033[H\033[2J");
        } else if (!running) {
            restoreTerminal();
        }
        output.print(screen);
        output.flush();
    }

    private void restoreTerminal() {
        if (alternateScreen) {
            output.print("\033[?25h\033[?1049l");
            alternateScreen = false;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            restoreTerminal();
            output.flush();
            closed = true;
        }
    }

    private void appendDetails(StringBuilder screen, MarketSnapshot snapshot) {
        screen.append("\n").append(heading(selectedSymbol + " | Latest one-minute OHLC")).append("\n");
        OhlcCandle candle = snapshot.latestCandle();
        if (candle == null) {
            screen.append("No executions yet\nO -- | H -- | L -- | C --\n");
        } else {
            String minute = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(candle.getMinuteStartTime()));
            screen.append(minute).append("\nO ").append(decimal(candle.getOpen(), 2, false))
                    .append(" | H ").append(decimal(candle.getHigh(), 2, false))
                    .append(" | L ").append(decimal(candle.getLow(), 2, false))
                    .append(" | C ").append(decimal(candle.getClose(), 2, false)).append("\n");
        }
        screen.append("\n").append(heading(selectedSymbol + " | Top 3 price levels")).append("\n\n");
        String format = "%-10s %6s   %-10s %6s%n";
        screen.append(String.format(Locale.ROOT, format, "BID", "SIZE", "ASK", "SIZE"));
        var bids = new ArrayList<>(snapshot.buyDepth().entrySet());
        var asks = new ArrayList<>(snapshot.sellDepth().entrySet());
        for (int i = 0; i < 3; i++) {
            screen.append(String.format(Locale.ROOT, format,
                    i < bids.size() ? decimal(bids.get(i).getKey(), 2, false) : "--",
                    i < bids.size() ? bids.get(i).getValue() : "--",
                    i < asks.size() ? decimal(asks.get(i).getKey(), 2, false) : "--",
                    i < asks.size() ? asks.get(i).getValue() : "--"));
        }
    }

    private String heading(String text) {
        return useColor ? "\033[1m" + text + "\033[0m" : text;
    }

    private String changeCell(BigDecimal value, boolean percent) {
        String text = decimal(value, 2, true) + (percent && value != null ? "%" : "");
        String padded = String.format(Locale.ROOT, "%10s", text);
        if (!useColor || value == null || value.signum() == 0) {
            return padded;
        }
        return (value.signum() > 0 ? "\033[32m" : "\033[31m") + padded + "\033[0m";
    }

    private static String decimal(BigDecimal value, int scale, boolean signed) {
        if (value == null) {
            return "--";
        }
        String text = value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
        return signed && value.signum() > 0 ? "+" + text : text;
    }
}
