package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ConsoleDashboardTest {

    private Exchange exchange() {
        Exchange exchange = new Exchange();
        exchange.registerStock(new Stock("AAPL", "Apple Inc.", new BigDecimal("100")));
        exchange.registerStock(new Stock("TSLA", "Tesla Inc.", new BigDecimal("200")));
        return exchange;
    }

    @Test
    void snapshotIncludesBookAndTradeStatisticsAndDoesNotChangeAfterTrading() {
        Exchange exchange = exchange();
        exchange.submitOrder("AAPL", new Order(1, OrderSide.BUY, new BigDecimal("99"), 5));
        exchange.submitOrder("AAPL", new Order(2, OrderSide.SELL, new BigDecimal("101"), 5));
        MarketSnapshot before = exchange.getMarketSnapshot(" aapl ");
        assertNull(before.lastTradedPrice());
        assertNull(before.priceChange());
        assertNull(before.priceChangePercent());
        assertNull(before.vwap());
        assertEquals(new BigDecimal("99"), before.bestBidPrice());
        assertEquals(new BigDecimal("101"), before.bestAskPrice());
        assertEquals(2, before.openOrderCount());

        exchange.submitOrder("AAPL", new Order(3, OrderSide.BUY, new BigDecimal("102"), 2));
        MarketSnapshot after = exchange.getMarketSnapshot("AAPL");
        assertEquals("AAPL", after.symbol());
        assertEquals(new BigDecimal("101"), after.lastTradedPrice());
        assertEquals(new BigDecimal("1"), after.priceChange());
        assertEquals(new BigDecimal("1.0000"), after.priceChangePercent());
        assertEquals(2, after.volume());
        assertEquals(1, after.tradeCount());
        assertEquals(2, after.openOrderCount());
        assertEquals(new BigDecimal("101.0000"), after.vwap());
        assertNull(before.lastTradedPrice());
        assertNull(before.latestCandle());
        assertEquals(5L, before.sellDepth().get(new BigDecimal("101")));
        assertEquals(3L, after.sellDepth().get(new BigDecimal("101")));
        assertEquals(after.lastTradedPrice(), after.latestCandle().getClose());
        assertThrows(UnsupportedOperationException.class, () -> after.buyDepth().clear());
        assertThrows(UnsupportedOperationException.class, () -> after.sellDepth().clear());
        assertEquals(0, before.volume());
        assertEquals(0, exchange.getMarketSnapshot("TSLA").tradeCount());
        assertThrows(IllegalArgumentException.class, () -> exchange.getMarketSnapshot("UNKNOWN"));
    }

    @Test
    void plainOutputShowsMissingValuesStatusAndAllStocksWithoutEscapeCodes() {
        Exchange exchange = exchange();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleDashboard dashboard = new ConsoleDashboard(exchange, List.of("aapl", "TSLA"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), false);
        dashboard.refresh(0, 0, true);
        dashboard.refresh(2, 7, false);
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("RUNNING"));
        assertTrue(output.contains("COMPLETED | Elapsed: 2s | Submitted orders: 7"));
        assertTrue(output.contains("AAPL"));
        assertTrue(output.contains("TSLA"));
        assertTrue(output.contains("--"));
        assertTrue(output.contains("No executions yet\nO -- | H -- | L -- | C --"));
        assertTrue(output.contains("AAPL | Top 3 price levels"));
        assertFalse(output.contains("null"));
        assertFalse(output.contains("\033"));
    }

    @Test
    void rendererFormatsPositiveAndNegativeChanges() {
        Exchange exchange = exchange();
        exchange.submitOrder("AAPL", new Order(1, OrderSide.SELL, new BigDecimal("101"), 2));
        exchange.submitOrder("AAPL", new Order(2, OrderSide.BUY, new BigDecimal("102"), 2));
        exchange.submitOrder("TSLA", new Order(1, OrderSide.BUY, new BigDecimal("198"), 3));
        exchange.submitOrder("TSLA", new Order(2, OrderSide.SELL, new BigDecimal("197"), 3));
        ConsoleDashboard dashboard = new ConsoleDashboard(exchange, List.of("AAPL", "TSLA"),
                System.out, false);
        String output = dashboard.render(1, 4, true);
        assertTrue(output.contains("+1.00%"));
        assertTrue(output.contains("-1.00%"));
        assertTrue(output.contains("+1.00"));
        assertTrue(output.contains("-2.00"));
        assertTrue(output.contains("101.00"));
        assertFalse(output.contains("101.0000"));
        assertTrue(output.contains("198.00"));
        assertFalse(output.contains("198.0000"));
    }

    @Test
    void interactiveOutputClearsTheScreenBeforeEachFrame() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleDashboard dashboard = new ConsoleDashboard(exchange(), List.of("AAPL"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), true);
        dashboard.refresh(0, 0, true);
        dashboard.refresh(1, 5, true);
        dashboard.refresh(2, 9, false);
        dashboard.close();
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("\033[?1049h\033[?25l\033[H\033[2JMINI STOCK EXCHANGE"));
        assertEquals(1, output.split("\\Q\033[?1049h\\E", -1).length - 1);
        assertEquals(2, output.split("\\Q\033[H\033[2J\\E", -1).length - 1);
        assertEquals(1, output.split("\\Q\033[?1049l\\E", -1).length - 1);
        assertTrue(output.contains("\033[?25h\033[?1049lMINI STOCK EXCHANGE"));
        assertTrue(output.indexOf("\033[?1049l") < output.indexOf("COMPLETED"));
    }

    @Test
    void closingAnInterruptedDashboardRestoresTerminalOnce() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleDashboard dashboard = new ConsoleDashboard(exchange(), List.of("AAPL"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), true);
        dashboard.refresh(0, 0, true);
        dashboard.close();
        String restored = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(restored.endsWith("\033[?25h\033[?1049l"));
        dashboard.close();
        assertEquals(restored, bytes.toString(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> dashboard.refresh(1, 1, true));
    }

    @Test
    void selectedStockShowsLatestCandleAndOnlyTopThreeDepthLevels() {
        Exchange exchange = exchange();
        exchange.submitOrder("TSLA", new Order(1, OrderSide.SELL, new BigDecimal("201"), 5));
        exchange.submitOrder("TSLA", new Order(2, OrderSide.BUY, new BigDecimal("201"), 2));
        exchange.submitOrder("TSLA", new Order(3, OrderSide.SELL, new BigDecimal("202"), 4));
        exchange.submitOrder("TSLA", new Order(4, OrderSide.SELL, new BigDecimal("203"), 6));
        exchange.submitOrder("TSLA", new Order(5, OrderSide.SELL, new BigDecimal("204"), 8));
        for (int i = 0; i < 4; i++) {
            exchange.submitOrder("TSLA", new Order(10 + i, OrderSide.BUY,
                    BigDecimal.valueOf(199 - i), i + 1));
        }
        ConsoleDashboard dashboard = new ConsoleDashboard(exchange, List.of("AAPL", "TSLA"),
                System.out, false, false, "tsla");
        String output = dashboard.render(1, 9, true);
        assertTrue(output.contains("TSLA | Latest one-minute OHLC"));
        assertTrue(output.contains("UTC\nO 201.00 | H 201.00 | L 201.00 | C 201.00"));
        String depth = output.substring(output.indexOf("TSLA | Top 3 price levels"));
        String[] rows = depth.split("\\R");
        assertEquals("BID SIZE ASK SIZE", rows[2].trim().replaceAll("\\s+", " "));
        assertEquals("199.00 1 201.00 3", rows[3].trim().replaceAll("\\s+", " "));
        assertEquals("198.00 2 202.00 4", rows[4].trim().replaceAll("\\s+", " "));
        assertEquals("197.00 3 203.00 6", rows[5].trim().replaceAll("\\s+", " "));
        assertFalse(depth.contains("204.00"));
        assertFalse(depth.contains("196.00"));
    }

    @Test
    void colorsAreOptionalAndDoNotChangeColumnAlignment() {
        Exchange exchange = exchange();
        exchange.submitOrder("AAPL", new Order(1, OrderSide.SELL, new BigDecimal("101"), 1));
        exchange.submitOrder("AAPL", new Order(2, OrderSide.BUY, new BigDecimal("101"), 1));
        exchange.submitOrder("TSLA", new Order(1, OrderSide.SELL, new BigDecimal("199"), 1));
        exchange.submitOrder("TSLA", new Order(2, OrderSide.BUY, new BigDecimal("199"), 1));
        ConsoleDashboard plain = new ConsoleDashboard(exchange, List.of("AAPL", "TSLA"),
                System.out, false, false, "AAPL");
        ConsoleDashboard colored = new ConsoleDashboard(exchange, List.of("AAPL", "TSLA"),
                System.out, false, true, "AAPL");
        String output = colored.render(1, 4, true);
        assertTrue(output.contains("\033[32m"));
        assertTrue(output.contains("\033[31m"));
        assertTrue(output.contains("\033[1m"));
        assertEquals(plain.render(1, 4, true), output.replaceAll("\033\\[[0-9;]*m", ""));
    }

    @Test
    void vwapRoundsOnlyForDisplay() {
        Exchange exchange = exchange();
        exchange.submitOrder("AAPL", new Order(1, OrderSide.SELL, new BigDecimal("100"), 1));
        exchange.submitOrder("AAPL", new Order(2, OrderSide.SELL, new BigDecimal("101"), 2));
        exchange.submitOrder("AAPL", new Order(3, OrderSide.BUY, new BigDecimal("101"), 3));
        ConsoleDashboard dashboard = new ConsoleDashboard(exchange, List.of("AAPL"), System.out, false);
        String row = dashboard.render(1, 3, true).lines()
                .filter(line -> line.startsWith("AAPL ") && !line.contains("|")).findFirst().orElseThrow();
        assertTrue(row.endsWith("100.67"));
        assertEquals(new BigDecimal("100.6667"), exchange.getVwap("AAPL"));
    }

    @Test
    void invalidDashboardConfigurationIsRejected() {
        Exchange exchange = exchange();
        assertThrows(IllegalArgumentException.class,
                () -> new ConsoleDashboard(null, List.of("AAPL"), System.out, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsoleDashboard(exchange, List.of(), System.out, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsoleDashboard(exchange, List.of("UNKNOWN"), System.out, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsoleDashboard(exchange, List.of("AAPL"), null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsoleDashboard(exchange, List.of("AAPL"), System.out, false, false, "TSLA"));
    }

    @Test
    void snapshotsStayConsistentWhileTradesExecute() throws Exception {
        Exchange exchange = exchange();
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var writer = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 1_000; i++) {
                    exchange.submitOrder("AAPL", new Order(2L * i + 1, OrderSide.SELL, BigDecimal.TEN, 1));
                    exchange.submitOrder("AAPL", new Order(2L * i + 2, OrderSide.BUY, BigDecimal.TEN, 1));
                }
                return null;
            });
            var reader = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    MarketSnapshot snapshot = exchange.getMarketSnapshot("AAPL");
                    assertEquals(snapshot.tradeCount(), snapshot.volume());
                    if (snapshot.tradeCount() == 0) {
                        assertNull(snapshot.lastTradedPrice());
                        assertNull(snapshot.vwap());
                    } else {
                        assertEquals(BigDecimal.TEN, snapshot.lastTradedPrice());
                        assertEquals(new BigDecimal("10.0000"), snapshot.vwap());
                        assertEquals(new BigDecimal("-90"), snapshot.priceChange());
                    }
                    if (snapshot.latestCandle() != null) {
                        assertEquals(snapshot.lastTradedPrice(), snapshot.latestCandle().getClose());
                    }
                    assertEquals(snapshot.openOrderCount(), snapshot.sellDepth().size());
                    assertTrue(snapshot.buyDepth().isEmpty());
                    assertTrue(snapshot.openOrderCount() <= 1);
                }
                return null;
            });
            start.countDown();
            writer.get();
            reader.get();
            assertEquals(1_000, exchange.getMarketSnapshot("AAPL").tradeCount());
        } finally {
            workers.shutdownNow();
        }
    }
}
