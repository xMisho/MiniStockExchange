package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceChangeTest {

    @Test
    void noExecutionMeansNoPriceChange() {
        Exchange exchange = exchange("100");
        assertNull(exchange.getPriceChange("AAPL"));
        assertNull(exchange.getPriceChangePercent("AAPL"));
        exchange.submitOrder("AAPL", new Order(10001, OrderSide.BUY, new BigDecimal("90"), 5));
        exchange.submitOrder("AAPL", new Order(10002, OrderSide.SELL, new BigDecimal("110"), 5));
        assertTrue(exchange.cancelOrder("AAPL", 10001));
        assertNull(exchange.getPriceChange("AAPL"));
        assertNull(exchange.getPriceChangePercent("AAPL"));
    }

    @Test
    void priceAboveReferenceProducesPositiveChangesAtRestingPrice() {
        Exchange exchange = exchange("100");
        exchange.submitOrder("AAPL", new Order(10101, OrderSide.SELL, new BigDecimal("110"), 5));
        exchange.submitOrder("AAPL", new Order(10102, OrderSide.BUY, new BigDecimal("115"), 5));
        assertEquals(new BigDecimal("10"), exchange.getPriceChange("AAPL"));
        assertEquals(new BigDecimal("10.0000"), exchange.getPriceChangePercent("AAPL"));
    }

    @Test
    void priceBelowReferenceProducesNegativeChanges() {
        Exchange exchange = exchange("100");
        trade(exchange, 10201, "95");
        assertEquals(new BigDecimal("-5"), exchange.getPriceChange("AAPL"));
        assertEquals(new BigDecimal("-5.0000"), exchange.getPriceChangePercent("AAPL"));
    }

    @Test
    void executionAtReferenceProducesZeroInsteadOfNull() {
        Exchange exchange = exchange("100.00");
        trade(exchange, 10301, "100");
        assertEquals(new BigDecimal("0.00"), exchange.getPriceChange("AAPL"));
        assertEquals(new BigDecimal("0.0000"), exchange.getPriceChangePercent("AAPL"));
    }

    @Test
    void percentageRoundsHalfUpForPositiveAndNegativeChanges() {
        Exchange exchange = exchange("128");
        trade(exchange, 10401, "129");
        assertEquals(new BigDecimal("0.7813"), exchange.getPriceChangePercent("AAPL"));
        trade(exchange, 10403, "127");
        assertEquals(new BigDecimal("-0.7813"), exchange.getPriceChangePercent("AAPL"));
        Exchange repeating = exchange("3");
        trade(repeating, 10405, "4");
        assertEquals(new BigDecimal("33.3333"), repeating.getPriceChangePercent("AAPL"));
    }

    @Test
    void nonTradingActivityDoesNotChangeExistingStatistics() {
        Exchange exchange = exchange("100");
        trade(exchange, 10501, "105");
        exchange.submitOrder("AAPL", new Order(10503, OrderSide.BUY, new BigDecimal("90"), 5));
        exchange.submitOrder("AAPL", new Order(10504, OrderSide.SELL, new BigDecimal("110"), 5));
        assertTrue(exchange.amendOrder("AAPL", 10503, new BigDecimal("91"), 3));
        assertTrue(exchange.cancelOrder("AAPL", 10503));
        assertTrue(exchange.cancelOrder("AAPL", 10504));
        exchange.submitOrder("AAPL", new Order(10505, OrderSide.BUY, OrderType.MARKET, null, 5));
        assertEquals(1, exchange.getTradeCount("AAPL"));
        assertEquals(new BigDecimal("5"), exchange.getPriceChange("AAPL"));
        assertEquals(new BigDecimal("5.0000"), exchange.getPriceChangePercent("AAPL"));
    }

    @Test
    void marketAndAmendmentExecutionsUseLatestPriceAndKeepReferenceFixed() {
        Exchange exchange = exchange("100");
        exchange.submitOrder("AAPL", new Order(10601, OrderSide.SELL, new BigDecimal("105"), 2));
        exchange.submitOrder("AAPL", new Order(10602, OrderSide.SELL, new BigDecimal("110"), 2));
        exchange.submitOrder("AAPL", new Order(10603, OrderSide.BUY, OrderType.MARKET, null, 4));
        assertEquals(new BigDecimal("10"), exchange.getPriceChange("AAPL"));
        assertEquals(new BigDecimal("10.0000"), exchange.getPriceChangePercent("AAPL"));
        exchange.submitOrder("AAPL", new Order(10604, OrderSide.SELL, new BigDecimal("95"), 2));
        exchange.submitOrder("AAPL", new Order(10605, OrderSide.BUY, new BigDecimal("90"), 2));
        assertTrue(exchange.amendOrder("AAPL", 10605, new BigDecimal("100"), 2));
        assertEquals(new BigDecimal("-5"), exchange.getPriceChange("AAPL"));
        assertEquals(new BigDecimal("-5.0000"), exchange.getPriceChangePercent("AAPL"));
        assertEquals(new BigDecimal("100"), exchange.getStock("AAPL").getReferencePrice());
    }

    @Test
    void priceChangesAreIsolatedByStockAndUnknownSymbolsAreRejected() {
        Exchange exchange = exchange("100");
        exchange.registerStock(new Stock("NVDA", "NVIDIA Corp.", new BigDecimal("200")));
        trade(exchange, 10701, "110");
        assertNull(exchange.getPriceChange("NVDA"));
        assertNull(exchange.getPriceChangePercent("NVDA"));
        exchange.submitOrder("NVDA", new Order(10701, OrderSide.SELL, new BigDecimal("190"), 1));
        exchange.submitOrder("NVDA", new Order(10702, OrderSide.BUY, new BigDecimal("190"), 1));
        assertEquals(new BigDecimal("10"), exchange.getPriceChange(" aapl "));
        assertEquals(new BigDecimal("10.0000"), exchange.getPriceChangePercent(" aapl "));
        assertEquals(new BigDecimal("-10"), exchange.getPriceChange("NVDA"));
        assertEquals(new BigDecimal("-5.0000"), exchange.getPriceChangePercent("NVDA"));
        for (String symbol : new String[]{null, "", " ", "UNKNOWN"}) {
            assertThrows(IllegalArgumentException.class, () -> exchange.getPriceChange(symbol));
            assertThrows(IllegalArgumentException.class, () -> exchange.getPriceChangePercent(symbol));
        }
    }

    private static Exchange exchange(String reference) {
        Exchange exchange = new Exchange();
        exchange.registerStock(new Stock("AAPL", "Apple Inc.", new BigDecimal(reference)));
        return exchange;
    }

    private static void trade(Exchange exchange, long id, String price) {
        exchange.submitOrder("AAPL", new Order(id, OrderSide.SELL, new BigDecimal(price), 1));
        exchange.submitOrder("AAPL", new Order(id + 1, OrderSide.BUY, new BigDecimal(price), 1));
    }
}
