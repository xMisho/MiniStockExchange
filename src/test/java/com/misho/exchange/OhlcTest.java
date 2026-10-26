package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OhlcTest {

    private static final long START = Instant.parse("2026-10-26T09:00:00Z").toEpochMilli();

    @Test
    void noExecutionsMeansNoCandles() {
        MatchingEngine engine = engine(new TestClock(START));
        assertTrue(engine.getOhlcCandles().isEmpty());
        assertNull(engine.getLatestOhlcCandle());
        engine.submitOrder(new Order(9001, OrderSide.BUY, OrderType.MARKET, null, 5));
        assertTrue(engine.getOhlcCandles().isEmpty());
        assertNull(engine.getLatestOhlcCandle());
    }

    @Test
    void firstExecutionSetsAllPricesAndSharesTheTradeTimestamp() {
        TestClock clock = new TestClock(START + 12_345);
        MatchingEngine engine = engine(clock);
        engine.submitOrder(new Order(9001, OrderSide.SELL, new BigDecimal("100"), 2));
        engine.submitOrder(new Order(9002, OrderSide.BUY, new BigDecimal("105"), 2));
        OhlcCandle candle = engine.getLatestOhlcCandle();
        assertEquals(START, candle.getMinuteStartTime());
        assertPrices(candle, "100", "100", "100", "100");
        assertEquals(clock.millis(), engine.getTradeHistory().get(0).getTimestamp());
        assertSame(candle, engine.getOhlcCandles().get(START));
    }

    @Test
    void sameMinuteTracksFirstHighestLowestAndLastExecutions() {
        TestClock clock = new TestClock(START);
        MatchingEngine engine = engine(clock);
        String[] prices = {"100", "105", "95", "102"};
        for (int i = 0; i < prices.length; i++) {
            clock.set(START + i * 10_000);
            trade(engine, 9101 + i * 2, prices[i]);
        }
        assertEquals(1, engine.getOhlcCandles().size());
        assertPrices(engine.getLatestOhlcCandle(), "100", "105", "95", "102");
    }

    @Test
    void minuteBoundaryStartsFreshCandleAndIdleMinutesAreOmitted() {
        TestClock clock = new TestClock(START + 59_999);
        MatchingEngine engine = engine(clock);
        trade(engine, 9201, "100");
        clock.set(START + 60_000);
        trade(engine, 9203, "110");
        clock.set(START + 180_000);
        trade(engine, 9205, "90");
        NavigableMap<Long, OhlcCandle> candles = engine.getOhlcCandles();
        assertEquals(List.of(START, START + 60_000, START + 180_000), List.copyOf(candles.keySet()));
        assertPrices(candles.get(START), "100", "100", "100", "100");
        assertPrices(candles.get(START + 60_000), "110", "110", "110", "110");
        assertPrices(candles.get(START + 180_000), "90", "90", "90", "90");
        clock.set(START + 600_000);
        assertEquals(3, engine.getOhlcCandles().size());
        assertEquals(START + 180_000, engine.getLatestOhlcCandle().getMinuteStartTime());
    }

    @Test
    void marketSweepRecordsEveryExecutionInTheMinute() {
        MatchingEngine engine = engine(new TestClock(START));
        engine.submitOrder(new Order(9301, OrderSide.BUY, new BigDecimal("105"), 2));
        engine.submitOrder(new Order(9302, OrderSide.BUY, new BigDecimal("100"), 2));
        engine.submitOrder(new Order(9303, OrderSide.BUY, new BigDecimal("95"), 2));
        engine.submitOrder(new Order(9304, OrderSide.SELL, OrderType.MARKET, null, 5));
        assertPrices(engine.getLatestOhlcCandle(), "105", "105", "95", "95");
        assertEquals(3, engine.getTradeCount());
        assertEquals(5, engine.getTotalTradedVolume());
    }

    @Test
    void amendmentTriggeredExecutionCreatesCandle() {
        MatchingEngine engine = engine(new TestClock(START));
        engine.submitOrder(new Order(9401, OrderSide.SELL, new BigDecimal("101"), 3));
        engine.submitOrder(new Order(9402, OrderSide.BUY, new BigDecimal("99"), 3));
        assertTrue(engine.getOhlcCandles().isEmpty());
        assertTrue(engine.amendOrder(9402, new BigDecimal("105"), 3));
        assertPrices(engine.getLatestOhlcCandle(), "101", "101", "101", "101");
    }

    @Test
    void nonTradingActivityDoesNotCreateOrModifyCandles() {
        TestClock clock = new TestClock(START);
        MatchingEngine engine = engine(clock);
        trade(engine, 9501, "100");
        clock.set(START + 60_000);
        engine.submitOrder(new Order(9503, OrderSide.BUY, new BigDecimal("90"), 5));
        engine.submitOrder(new Order(9504, OrderSide.SELL, new BigDecimal("110"), 5));
        assertTrue(engine.amendOrder(9503, new BigDecimal("91"), 3));
        assertTrue(engine.cancelOrder(9503));
        assertTrue(engine.cancelOrder(9504));
        engine.submitOrder(new Order(9505, OrderSide.BUY, OrderType.MARKET, null, 3));
        assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(
                new Order(9505, OrderSide.SELL, new BigDecimal("200"), 1)));
        assertEquals(List.of(START), List.copyOf(engine.getOhlcCandles().keySet()));
        assertPrices(engine.getLatestOhlcCandle(), "100", "100", "100", "100");
    }

    @Test
    void snapshotsCannotBeChangedAndDoNotFollowLaterTrades() {
        TestClock clock = new TestClock(START);
        MatchingEngine engine = engine(clock);
        trade(engine, 9601, "100");
        NavigableMap<Long, OhlcCandle> before = engine.getOhlcCandles();
        OhlcCandle firstSnapshot = before.get(START);
        assertThrows(UnsupportedOperationException.class, before::clear);
        assertThrows(UnsupportedOperationException.class, () -> before.put(START + 60_000, firstSnapshot));
        assertThrows(UnsupportedOperationException.class, () -> before.firstEntry().setValue(firstSnapshot));
        trade(engine, 9603, "110");
        clock.set(START + 60_000);
        trade(engine, 9605, "90");
        assertEquals(1, before.size());
        assertPrices(firstSnapshot, "100", "100", "100", "100");
        assertPrices(engine.getOhlcCandles().get(START), "100", "110", "100", "110");
        assertEquals(2, engine.getOhlcCandles().size());
    }

    @Test
    void exchangeKeepsStockCandlesSeparateAndRejectsUnknownSymbols() {
        Exchange exchange = new Exchange();
        exchange.registerStock(new Stock("AAPL", "Apple Inc.", new BigDecimal("250")));
        exchange.registerStock(new Stock("NVDA", "NVIDIA Corp.", new BigDecimal("190")));
        assertTrue(exchange.getOhlcCandles("AAPL").isEmpty());
        assertNull(exchange.getLatestOhlcCandle("AAPL"));
        exchange.submitOrder("AAPL", new Order(9701, OrderSide.SELL, new BigDecimal("100"), 1));
        exchange.submitOrder("AAPL", new Order(9702, OrderSide.BUY, new BigDecimal("105"), 1));
        assertTrue(exchange.getOhlcCandles("NVDA").isEmpty());
        exchange.submitOrder("NVDA", new Order(9701, OrderSide.BUY, new BigDecimal("200"), 1));
        exchange.submitOrder("NVDA", new Order(9702, OrderSide.SELL, new BigDecimal("195"), 1));
        assertPrices(exchange.getLatestOhlcCandle(" aapl "), "100", "100", "100", "100");
        assertPrices(exchange.getLatestOhlcCandle("NVDA"), "200", "200", "200", "200");
        assertEquals(exchange.getLastTradedPrice("AAPL"), exchange.getLatestOhlcCandle("AAPL").getClose());
        assertEquals(exchange.getLastTradedPrice("NVDA"), exchange.getLatestOhlcCandle("NVDA").getClose());
        assertThrows(IllegalArgumentException.class, () -> exchange.getOhlcCandles("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> exchange.getLatestOhlcCandle(null));
        assertThrows(IllegalArgumentException.class, () -> exchange.getOhlcCandles(" "));
    }

    @Test
    void highAndLowUseNumericBigDecimalOrdering() {
        MatchingEngine engine = engine(new TestClock(START));
        trade(engine, 9801, "9");
        trade(engine, 9803, "100");
        trade(engine, 9805, "10.00");
        trade(engine, 9807, "9.000");
        assertPrices(engine.getLatestOhlcCandle(), "9", "100", "9", "9.000");
    }

    private static MatchingEngine engine(Clock clock) {
        return new MatchingEngine(new OrderBook(), null, clock);
    }

    private static void trade(MatchingEngine engine, long id, String price) {
        engine.submitOrder(new Order(id, OrderSide.SELL, new BigDecimal(price), 1));
        engine.submitOrder(new Order(id + 1, OrderSide.BUY, new BigDecimal(price), 1));
    }

    private static void assertPrices(OhlcCandle candle, String open, String high, String low, String close) {
        assertNotNull(candle);
        assertEquals(new BigDecimal(open), candle.getOpen());
        assertEquals(new BigDecimal(high), candle.getHigh());
        assertEquals(new BigDecimal(low), candle.getLow());
        assertEquals(new BigDecimal(close), candle.getClose());
    }

    private static final class TestClock extends Clock {
        private final AtomicLong time;
        private final ZoneId zone;

        TestClock(long millis) {
            this(new AtomicLong(millis), ZoneOffset.UTC);
        }

        private TestClock(AtomicLong time, ZoneId zone) {
            this.time = time;
            this.zone = zone;
        }

        void set(long millis) {
            time.set(millis);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new TestClock(time, zone);
        }

        @Override
        public long millis() {
            return time.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }
}
