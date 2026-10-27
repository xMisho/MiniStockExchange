package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SubmissionValidationTest {

    @Test
    void inactiveOrdersAreRejectedWithoutChangingBookOrReservingId() {
        for (boolean filled : new boolean[]{false, true}) {
            MatchingEngine original = new MatchingEngine(new OrderBook());
            Order sell = new Order(1, OrderSide.SELL, BigDecimal.TEN, 5);
            original.submitOrder(sell);
            if (filled) {
                original.submitOrder(new Order(2, OrderSide.BUY, BigDecimal.TEN, 5));
            } else {
                original.cancelOrder(1);
            }

            OrderBook book = new OrderBook();
            MatchingEngine engine = new MatchingEngine(book);
            Order buy = new Order(3, OrderSide.BUY, BigDecimal.TEN, 5);
            engine.submitOrder(buy);

            assertThrows(IllegalArgumentException.class, () -> engine.submitOrder(sell));
            assertThrows(IllegalArgumentException.class, () -> book.addOrder(sell));
            assertEquals(1, engine.getOpenOrderCount());
            assertEquals(5, buy.getRemainingQuantity());
            assertEquals(0, engine.getTradeCount());

            engine.submitOrder(new Order(1, OrderSide.SELL, BigDecimal.TEN, 5));
            assertEquals(1, engine.getTradeCount());
            assertTrue(buy.isFilled());
        }
    }

    @Test
    void symbolsAreNormalizedIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Stock stock = new Stock(" ibm ", "IBM", BigDecimal.TEN);
            Exchange exchange = new Exchange();
            exchange.registerStock(stock);

            assertEquals("IBM", stock.getSymbol());
            assertSame(stock, exchange.getStock("IBM"));
            assertSame(stock, exchange.getStock(" ibm "));
            exchange.submitOrder("ibm", new Order(1, OrderSide.BUY, BigDecimal.TEN, 5));
            assertEquals(1, exchange.getOpenOrderCount("IBM"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
