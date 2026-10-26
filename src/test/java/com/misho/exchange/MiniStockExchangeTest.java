package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.NavigableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(30)
class MiniStockExchangeTest {

    @Test
    void recordsTradeAndPartialFill() {
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        Order sell = new Order(23, OrderSide.SELL, new BigDecimal("100"), 4);
        Order buy = new Order(15, OrderSide.BUY, new BigDecimal("101"), 10);

        engine.submitOrder(sell);
        engine.submitOrder(buy);

        List<Trade> history = engine.getTradeHistory();
        if (history.size() != 1) {
            throw new AssertionError("Expected exactly one trade.");
        }

        Trade trade = history.get(0);
        if (trade.getBuyOrderId() != 15 || trade.getSellOrderId() != 23
                || trade.getExecutionPrice().compareTo(new BigDecimal("100")) != 0
                || trade.getQuantity() != 4) {
            throw new AssertionError("Trade details do not match expectations.");
        }
        if (buy.getRemainingQuantity() != 6 || !sell.isFilled()) {
            throw new AssertionError("Incorrect remaining quantities.");
        }

        System.out.println("Trade count: " + history.size());
        System.out.println("Buy order ID: " + trade.getBuyOrderId());
        System.out.println("Sell order ID: " + trade.getSellOrderId());
        System.out.println("Execution price: " + trade.getExecutionPrice());
        System.out.println("Quantity: " + trade.getQuantity());
        System.out.println("Trade history test passed.");

    }

    @Test
    void cancelsOrder() {
        System.out.println("\n=== CANCELLATION TEST ===");
        OrderBook book = new OrderBook();
        MatchingEngine cancellationEngine = new MatchingEngine(book);
        Order order = new Order(50, OrderSide.BUY, new BigDecimal("100"), 10);
        cancellationEngine.submitOrder(order);

        boolean firstCancellation = cancellationEngine.cancelOrder(50);
        boolean secondCancellation = cancellationEngine.cancelOrder(50);
        System.out.println("First cancellation: " + firstCancellation + " (expected true)");
        System.out.println("Second cancellation: " + secondCancellation + " (expected false)");

        if (!firstCancellation || secondCancellation || book.getBestBuyOrder() != null) {
            throw new AssertionError("Order cancellation failed.");
        }
        System.out.println("Cancellation test passed.");

    }

    @Test
    void reportsMarketStatistics() {
        System.out.println("\n=== MARKET STATISTICS TEST ===");
        OrderBook statsBook = new OrderBook();
        MatchingEngine statsEngine = new MatchingEngine(statsBook);
        if (statsBook.getBestBidPrice() != null || statsBook.getBestAskPrice() != null
                || statsBook.getSpread() != null || statsBook.getOpenOrderCount() != 0
                || statsBook.getBuyOrderCount() != 0 || statsBook.getSellOrderCount() != 0
                || statsEngine.getTradeCount() != 0 || statsEngine.getTotalTradedVolume() != 0) {
            throw new AssertionError("Incorrect empty market statistics.");
        }

        statsEngine.submitOrder(new Order(60, OrderSide.BUY, new BigDecimal("99"), 10));
        if (statsBook.getBestAskPrice() != null || statsBook.getSpread() != null) {
            throw new AssertionError("A one-sided book should have no spread.");
        }
        statsEngine.submitOrder(new Order(61, OrderSide.BUY, new BigDecimal("99"), 5));
        statsEngine.submitOrder(new Order(62, OrderSide.SELL, new BigDecimal("101"), 4));
        statsEngine.submitOrder(new Order(63, OrderSide.SELL, new BigDecimal("102"), 8));
        if (statsBook.getBestBidPrice().compareTo(new BigDecimal("99")) != 0
                || statsBook.getBestAskPrice().compareTo(new BigDecimal("101")) != 0
                || statsBook.getSpread().compareTo(new BigDecimal("2")) != 0
                || statsBook.getBuyOrderCount() != 2 || statsBook.getSellOrderCount() != 2
                || statsBook.getOpenOrderCount() != 4) {
            throw new AssertionError("Incorrect resting book statistics.");
        }
        System.out.println("Best bid: " + statsBook.getBestBidPrice());
        System.out.println("Best ask: " + statsBook.getBestAskPrice());
        System.out.println("Spread: " + statsBook.getSpread());

        statsEngine.submitOrder(new Order(64, OrderSide.BUY, new BigDecimal("105"), 10));
        if (statsEngine.getTradeCount() != 2 || statsEngine.getTotalTradedVolume() != 10
                || statsBook.getBuyOrderCount() != 2 || statsBook.getSellOrderCount() != 1
                || statsBook.getOpenOrderCount() != 3
                || statsBook.getBestAskPrice().compareTo(new BigDecimal("102")) != 0
                || statsBook.getSpread().compareTo(new BigDecimal("3")) != 0) {
            throw new AssertionError("Incorrect statistics after matching.");
        }
        if (statsBook.getOpenOrderCount()
                != statsBook.getBuyOrderCount() + statsBook.getSellOrderCount()) {
            throw new AssertionError("Open order count must equal buy orders plus sell orders.");
        }
        System.out.println("Buy orders: " + statsBook.getBuyOrderCount());
        System.out.println("Sell orders: " + statsBook.getSellOrderCount());
        System.out.println("Open orders: " + statsBook.getOpenOrderCount());

        if (!statsEngine.cancelOrder(63) || statsBook.getSellOrderCount() != 0
                || statsBook.getOpenOrderCount() != 2 || statsBook.getBestAskPrice() != null
                || statsBook.getSpread() != null || statsEngine.getTradeCount() != 2
                || statsEngine.getTotalTradedVolume() != 10) {
            throw new AssertionError("Incorrect statistics after cancellation.");
        }
        System.out.println("Trade count: " + statsEngine.getTradeCount());
        System.out.println("Total traded volume: " + statsEngine.getTotalTradedVolume());
        System.out.println("Market statistics test passed.");

    }

    @Test
    void rejectsDuplicateIds() {
        System.out.println("\n=== DUPLICATE ORDER ID TEST ===");
        MatchingEngine duplicateIdEngine = new MatchingEngine(new OrderBook());
        duplicateIdEngine.submitOrder(
                new Order(100, OrderSide.BUY, new BigDecimal("99"), 5)
        );

        try {
            duplicateIdEngine.submitOrder(
                    new Order(100, OrderSide.SELL, new BigDecimal("101"), 5)
            );
            throw new AssertionError("Duplicate order ID was accepted.");
        } catch (IllegalArgumentException e) {
            System.out.println("Duplicate ID correctly rejected.");
        }

    }

    @Test
    void matchesSamePriceInFifoOrder() {
        System.out.println("\n=== FIFO AT THE SAME PRICE TEST ===");
        OrderBook fifoBook = new OrderBook();
        MatchingEngine fifoEngine = new MatchingEngine(fifoBook);
        Order firstSell = new Order(101, OrderSide.SELL, new BigDecimal("100"), 3);
        Order secondSell = new Order(102, OrderSide.SELL, new BigDecimal("100"), 3);
        Order thirdSell = new Order(103, OrderSide.SELL, new BigDecimal("100"), 3);
        Order fifoBuy = new Order(200, OrderSide.BUY, new BigDecimal("100"), 5);

        fifoEngine.submitOrder(firstSell);
        fifoEngine.submitOrder(secondSell);
        fifoEngine.submitOrder(thirdSell);
        fifoEngine.submitOrder(fifoBuy);

        List<Trade> fifoTrades = fifoEngine.getTradeHistory();
        if (fifoTrades.size() != 2) {
            throw new AssertionError("FIFO test expected exactly two trades.");
        }
        Trade firstTrade = fifoTrades.get(0);
        Trade secondTrade = fifoTrades.get(1);
        if (firstTrade.getBuyOrderId() != 200 || firstTrade.getSellOrderId() != 101
                || firstTrade.getQuantity() != 3
                || secondTrade.getBuyOrderId() != 200 || secondTrade.getSellOrderId() != 102
                || secondTrade.getQuantity() != 2
                || firstTrade.getExecutionPrice().compareTo(new BigDecimal("100")) != 0
                || secondTrade.getExecutionPrice().compareTo(new BigDecimal("100")) != 0) {
            throw new AssertionError("Trades did not follow FIFO order.");
        }
        if (!firstSell.isFilled() || !fifoBuy.isFilled()
                || secondSell.getRemainingQuantity() != 1
                || thirdSell.getRemainingQuantity() != 3
                || fifoBook.getBestSellOrder() != secondSell
                || fifoBook.getSellOrderCount() != 2 || fifoBook.getBuyOrderCount() != 0
                || fifoBook.getOpenOrderCount() != 2 || fifoEngine.getTotalTradedVolume() != 5) {
            throw new AssertionError("Incorrect book or remaining quantities after FIFO matching.");
        }
        if (fifoEngine.cancelOrder(101) || !fifoEngine.cancelOrder(102)
                || fifoBook.getBestSellOrder() != thirdSell) {
            throw new AssertionError("Incorrect remaining FIFO queue.");
        }
        System.out.println("Trade 1: BUY #200 vs SELL #101, quantity 3");
        System.out.println("Trade 2: BUY #200 vs SELL #102, quantity 2");
        System.out.println("FIFO test passed.");

    }

    @Test
    void incomingBuyUsesRestingSellPrice() {
        System.out.println("\n=== INCOMING BUY EXECUTION PRICE TEST ===");
        MatchingEngine incomingBuyEngine = new MatchingEngine(new OrderBook());
        incomingBuyEngine.submitOrder(
                new Order(301, OrderSide.SELL, new BigDecimal("100"), 5));
        incomingBuyEngine.submitOrder(
                new Order(302, OrderSide.BUY, new BigDecimal("105"), 5));
        List<Trade> incomingBuyTrades = incomingBuyEngine.getTradeHistory();
        if (incomingBuyTrades.size() != 1
                || incomingBuyTrades.get(0).getExecutionPrice().compareTo(new BigDecimal("100")) != 0
                || incomingBuyTrades.get(0).getBuyOrderId() != 302
                || incomingBuyTrades.get(0).getSellOrderId() != 301
                || incomingBuyTrades.get(0).getQuantity() != 5) {
            throw new AssertionError("Incoming BUY must execute at the resting SELL price of 100.");
        }
        System.out.println("Incoming BUY execution price test passed: 100.");

    }

    @Test
    void incomingSellUsesRestingBuyPrice() {
        System.out.println("\n=== INCOMING SELL EXECUTION PRICE TEST ===");
        MatchingEngine incomingSellEngine = new MatchingEngine(new OrderBook());
        incomingSellEngine.submitOrder(
                new Order(401, OrderSide.BUY, new BigDecimal("105"), 5));
        incomingSellEngine.submitOrder(
                new Order(402, OrderSide.SELL, new BigDecimal("100"), 5));
        List<Trade> incomingSellTrades = incomingSellEngine.getTradeHistory();
        if (incomingSellTrades.size() != 1
                || incomingSellTrades.get(0).getExecutionPrice().compareTo(new BigDecimal("105")) != 0
                || incomingSellTrades.get(0).getBuyOrderId() != 401
                || incomingSellTrades.get(0).getSellOrderId() != 402
                || incomingSellTrades.get(0).getQuantity() != 5) {
            throw new AssertionError("Incoming SELL must execute at the resting BUY price of 105.");
        }
        System.out.println("Incoming SELL execution price test passed: 105.");

    }

    @Test
    void cancelsNonBestPriceLevel() {
        System.out.println("\n=== NON-BEST PRICE LEVEL CANCELLATION TEST ===");
        OrderBook deeperBook = new OrderBook();
        MatchingEngine deeperEngine = new MatchingEngine(deeperBook);
        Order bestAsk = new Order(501, OrderSide.SELL, new BigDecimal("100"), 3);
        Order deeperAsk = new Order(502, OrderSide.SELL, new BigDecimal("105"), 7);
        deeperEngine.submitOrder(bestAsk);
        deeperEngine.submitOrder(deeperAsk);

        if (!deeperEngine.cancelOrder(502) || deeperEngine.cancelOrder(502)
                || deeperBook.getBestSellOrder() == null
                || deeperBook.getBestSellOrder().getOrderId() != 501
                || deeperBook.getBestAskPrice().compareTo(new BigDecimal("100")) != 0
                || bestAsk.getRemainingQuantity() != 3 || deeperAsk.getRemainingQuantity() != 7
                || deeperBook.getSellOrderCount() != 1 || deeperBook.getOpenOrderCount() != 1
                || deeperEngine.getTradeCount() != 0) {
            throw new AssertionError("Cancelling a deeper order must leave the best ask unaffected.");
        }
        if (!deeperEngine.cancelOrder(501) || deeperBook.getBestAskPrice() != null
                || deeperBook.getOpenOrderCount() != 0) {
            throw new AssertionError("Cancelled price levels must not remain in the book.");
        }
        System.out.println("Non-best price level cancellation test passed.");

    }

    @Test
    void matchesConcurrentOrdersWhileReadingStatistics() throws InterruptedException {
        System.out.println("\n=== MULTITHREADED STRESS TEST ===");

        MatchingEngine engine = new MatchingEngine(new OrderBook());

        int ordersPerThread = 1_000;

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread buyer1 = new Thread(() -> {
            try {
                startGate.await();

                for (int i = 0; i < ordersPerThread; i++) {
                    engine.submitOrder(new Order(
                            1_000_000L + i,
                            OrderSide.BUY,
                            new BigDecimal("101"),
                            1
                    ));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread buyer2 = new Thread(() -> {
            try {
                startGate.await();

                for (int i = 0; i < ordersPerThread; i++) {
                    engine.submitOrder(new Order(
                            2_000_000L + i,
                            OrderSide.BUY,
                            new BigDecimal("101"),
                            1
                    ));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread seller1 = new Thread(() -> {
            try {
                startGate.await();

                for (int i = 0; i < ordersPerThread; i++) {
                    engine.submitOrder(new Order(
                            3_000_000L + i,
                            OrderSide.SELL,
                            new BigDecimal("100"),
                            1
                    ));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread seller2 = new Thread(() -> {
            try {
                startGate.await();

                for (int i = 0; i < ordersPerThread; i++) {
                    engine.submitOrder(new Order(
                            4_000_000L + i,
                            OrderSide.SELL,
                            new BigDecimal("100"),
                            1
                    ));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread reader = new Thread(() -> {
            try {
                startGate.await();

                for (int i = 0; i < 5_000; i++) {
                    engine.getBestBidPrice();
                    engine.getBestAskPrice();
                    engine.getSpread();
                    engine.getOpenOrderCount();
                    engine.getBuyOrderCount();
                    engine.getSellOrderCount();
                    engine.getTradeHistory();
                    engine.getTradeCount();
                    engine.getTotalTradedVolume();
                    OhlcCandle candle = engine.getLatestOhlcCandle();
                    if (candle != null) {
                        assertTrue(candle.getLow().compareTo(candle.getOpen()) <= 0);
                        assertTrue(candle.getHigh().compareTo(candle.getOpen()) >= 0);
                        assertTrue(candle.getLow().compareTo(candle.getClose()) <= 0);
                        assertTrue(candle.getHigh().compareTo(candle.getClose()) >= 0);
                    }
                    engine.getOhlcCandles();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        buyer1.start();
        buyer2.start();
        seller1.start();
        seller2.start();
        reader.start();

        // Release everybody at roughly the same time.
        startGate.countDown();

        buyer1.join();
        buyer2.join();
        seller1.join();
        seller2.join();
        reader.join();

        if (failure.get() != null) {
            throw new AssertionError(
                    "A worker thread failed.",
                    failure.get()
            );
        }

        int expectedTrades = ordersPerThread * 2;
        long expectedVolume = expectedTrades;

        if (engine.getTradeCount() != expectedTrades) {
            throw new AssertionError(
                    "Expected " + expectedTrades
                            + " trades but got "
                            + engine.getTradeCount()
            );
        }

        if (engine.getTotalTradedVolume() != expectedVolume) {
            throw new AssertionError(
                    "Incorrect total traded volume."
            );
        }

        if (engine.getOpenOrderCount() != 0
                || engine.getBuyOrderCount() != 0
                || engine.getSellOrderCount() != 0) {

            throw new AssertionError(
                    "All submitted orders should have been matched."
            );
        }

        System.out.println("Trades: " + engine.getTradeCount());
        System.out.println("Volume: " + engine.getTotalTradedVolume());
        System.out.println("Open orders: " + engine.getOpenOrderCount());
        System.out.println("Multithreaded stress test passed.");
    }

    @Test
    void invalidAmendmentPricesLeaveBookUnchanged() {
        OrderBook book = new OrderBook();
        Order first = new Order(701, OrderSide.SELL, new BigDecimal("100"), 5);
        book.addOrder(first);
        book.addOrder(new Order(702, OrderSide.SELL, new BigDecimal("100"), 5));

        for (BigDecimal price : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            assertThrows(IllegalArgumentException.class, () -> book.amendOrder(701, price, 8));
            assertSame(first, book.findOrder(701));
            assertSame(first, book.getBestSellOrder());
            assertEquals(new BigDecimal("100"), first.getPrice());
            assertEquals(5, first.getRemainingQuantity());
            assertEquals(2, book.getOpenOrderCount());
        }
    }

    @Test
    void invalidAmendmentQuantitiesLeaveBookUnchanged() {
        OrderBook book = new OrderBook();
        Order first = new Order(703, OrderSide.BUY, new BigDecimal("100"), 5);
        book.addOrder(first);
        book.addOrder(new Order(704, OrderSide.BUY, new BigDecimal("100"), 5));

        for (int quantity : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> book.amendOrder(703, new BigDecimal("101"), quantity));
            assertSame(first, book.findOrder(703));
            assertSame(first, book.getBestBuyOrder());
            assertEquals(new BigDecimal("100"), first.getPrice());
            assertEquals(5, first.getRemainingQuantity());
            assertEquals(2, book.getOpenOrderCount());
        }
    }

    @Test
    void absentOrdersReturnNullBeforeValidatingAmendmentValues() {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new MatchingEngine(book);
        assertNull(book.amendOrder(9999, null, -5));

        engine.submitOrder(new Order(801, OrderSide.BUY, new BigDecimal("100"), 5));
        engine.cancelOrder(801);
        assertNull(book.amendOrder(801, null, -5));

        engine.submitOrder(new Order(802, OrderSide.SELL, new BigDecimal("100"), 5));
        engine.submitOrder(new Order(803, OrderSide.BUY, new BigDecimal("100"), 5));
        assertNull(book.amendOrder(802, null, -5));
        assertNull(book.amendOrder(803, null, -5));
        assertEquals(0, book.getOpenOrderCount());
    }

    @Test
void amendReducingQuantityPreservesFifoPriority() {
    MatchingEngine engine = new MatchingEngine(new OrderBook());

    Order firstSell = new Order(
            1001,
            OrderSide.SELL,
            new BigDecimal("100"),
            5
    );

    Order secondSell = new Order(
            1002,
            OrderSide.SELL,
            new BigDecimal("100"),
            5
    );

    engine.submitOrder(firstSell);
    engine.submitOrder(secondSell);

    boolean amended = engine.amendOrder(
            1001,
            new BigDecimal("100"),
            3
    );

    assertTrue(amended);

    Order buy = new Order(
            1003,
            OrderSide.BUY,
            new BigDecimal("100"),
            4
    );

    engine.submitOrder(buy);

    List<Trade> trades = engine.getTradeHistory();

    assertEquals(2, trades.size());

    assertEquals(1001, trades.get(0).getSellOrderId());
    assertEquals(3, trades.get(0).getQuantity());

    assertEquals(1002, trades.get(1).getSellOrderId());
    assertEquals(1, trades.get(1).getQuantity());

    assertTrue(firstSell.isFilled());
    assertEquals(4, secondSell.getRemainingQuantity());
}
@Test
void amendIncreasingQuantityLosesFifoPriority() {
    MatchingEngine engine = new MatchingEngine(new OrderBook());

    Order firstSell = new Order(
            1101,
            OrderSide.SELL,
            new BigDecimal("100"),
            5
    );

    Order secondSell = new Order(
            1102,
            OrderSide.SELL,
            new BigDecimal("100"),
            5
    );

    engine.submitOrder(firstSell);
    engine.submitOrder(secondSell);

    boolean amended = engine.amendOrder(
            1101,
            new BigDecimal("100"),
            8
    );

    assertTrue(amended);

    Order buy = new Order(
            1103,
            OrderSide.BUY,
            new BigDecimal("100"),
            6
    );

    engine.submitOrder(buy);

    List<Trade> trades = engine.getTradeHistory();

    assertEquals(2, trades.size());

    assertEquals(1102, trades.get(0).getSellOrderId());
    assertEquals(5, trades.get(0).getQuantity());

    assertEquals(1101, trades.get(1).getSellOrderId());
    assertEquals(1, trades.get(1).getQuantity());

    assertTrue(secondSell.isFilled());
    assertEquals(7, firstSell.getRemainingQuantity());
}

@Test
void amendChangingPriceMovesOrderAndLosesFifoPriority() {
    MatchingEngine engine = new MatchingEngine(new OrderBook());

    Order firstSell = new Order(
            1201,
            OrderSide.SELL,
            new BigDecimal("100"),
            5
    );

    Order secondSell = new Order(
            1202,
            OrderSide.SELL,
            new BigDecimal("101"),
            5
    );

    engine.submitOrder(firstSell);
    engine.submitOrder(secondSell);

    boolean amended = engine.amendOrder(
            1201,
            new BigDecimal("101"),
            5
    );

    assertTrue(amended);
    assertEquals(new BigDecimal("101"), firstSell.getPrice());

    Order buy = new Order(
            1203,
            OrderSide.BUY,
            new BigDecimal("101"),
            6
    );

    engine.submitOrder(buy);

    List<Trade> trades = engine.getTradeHistory();

    assertEquals(2, trades.size());

    assertEquals(1202, trades.get(0).getSellOrderId());
    assertEquals(5, trades.get(0).getQuantity());

    assertEquals(1201, trades.get(1).getSellOrderId());
    assertEquals(1, trades.get(1).getQuantity());

    assertTrue(secondSell.isFilled());
    assertEquals(4, firstSell.getRemainingQuantity());
}

@Test
void amendPriceCrossingSpreadTriggersImmediateTrade() {
    MatchingEngine engine = new MatchingEngine(new OrderBook());

    Order buy = new Order(
            1301,
            OrderSide.BUY,
            new BigDecimal("95"),
            5
    );

    Order sell = new Order(
            1302,
            OrderSide.SELL,
            new BigDecimal("100"),
            3
    );

    engine.submitOrder(buy);
    engine.submitOrder(sell);

    assertEquals(0, engine.getTradeCount());

    boolean amended = engine.amendOrder(
            1301,
            new BigDecimal("101"),
            5
    );

    assertTrue(amended);

    List<Trade> trades = engine.getTradeHistory();

    assertEquals(1, trades.size());

    Trade trade = trades.get(0);

    assertEquals(1301, trade.getBuyOrderId());
    assertEquals(1302, trade.getSellOrderId());
    assertEquals(new BigDecimal("100"), trade.getExecutionPrice());
    assertEquals(3, trade.getQuantity());

    assertTrue(sell.isFilled());
    assertEquals(2, buy.getRemainingQuantity());

    assertEquals(1, engine.getOpenOrderCount());
    assertEquals(new BigDecimal("101"), engine.getBestBidPrice());
    assertNull(engine.getBestAskPrice());
}

@Test
void registersStocksAndRejectsDuplicateSymbols() {
    Exchange exchange = new Exchange();

    Stock apple = new Stock(
            "AAPL",
            "Apple Inc.",
            new BigDecimal("250.00")
    );

    Stock nvidia = new Stock(
            "nvda",
            "NVIDIA Corp.",
            new BigDecimal("190.00")
    );

    exchange.registerStock(apple);
    exchange.registerStock(nvidia);

    assertEquals(2, exchange.getStockCount());

    assertSame(apple, exchange.getStock("AAPL"));
    assertSame(apple, exchange.getStock("aapl"));

    assertSame(nvidia, exchange.getStock("NVDA"));
    assertEquals("NVDA", nvidia.getSymbol());

    Stock duplicateApple = new Stock(
            " aapl ",
            "Different Apple",
            new BigDecimal("300.00")
    );

    assertThrows(
            IllegalArgumentException.class,
            () -> exchange.registerStock(duplicateApple)
    );

    assertEquals(2, exchange.getStockCount());
}

@Test
void stockOrderBooksAreIsolated() {
    Exchange exchange = new Exchange();

    exchange.registerStock(new Stock(
            "AAPL",
            "Apple Inc.",
            new BigDecimal("250.00")
    ));

    exchange.registerStock(new Stock(
            "NVDA",
            "NVIDIA Corp.",
            new BigDecimal("190.00")
    ));

    Order appleBuy = new Order(
            1,
            OrderSide.BUY,
            new BigDecimal("150"),
            5
    );

    Order nvidiaSell = new Order(
            1,
            OrderSide.SELL,
            new BigDecimal("100"),
            5
    );

    exchange.submitOrder("AAPL", appleBuy);
    exchange.submitOrder("NVDA", nvidiaSell);

    assertEquals(0, exchange.getTradeCount("AAPL"));
    assertEquals(0, exchange.getTradeCount("NVDA"));

    assertEquals(1, exchange.getOpenOrderCount("AAPL"));
    assertEquals(1, exchange.getOpenOrderCount("NVDA"));

    Order appleSell = new Order(
            2,
            OrderSide.SELL,
            new BigDecimal("140"),
            5
    );

    exchange.submitOrder("AAPL", appleSell);

    assertEquals(1, exchange.getTradeCount("AAPL"));
    assertEquals(0, exchange.getTradeCount("NVDA"));

    assertEquals(0, exchange.getOpenOrderCount("AAPL"));
    assertEquals(1, exchange.getOpenOrderCount("NVDA"));
}

@Test
void cancellationAndAmendmentAreRoutedToCorrectStock() {
    Exchange exchange = new Exchange();

    exchange.registerStock(new Stock(
            "AAPL",
            "Apple Inc.",
            new BigDecimal("250.00")
    ));

    exchange.registerStock(new Stock(
            "NVDA",
            "NVIDIA Corp.",
            new BigDecimal("190.00")
    ));

    Order appleBuy = new Order(
            10,
            OrderSide.BUY,
            new BigDecimal("90"),
            5
    );

    Order appleSell = new Order(
            11,
            OrderSide.SELL,
            new BigDecimal("100"),
            3
    );

    Order nvidiaBuy = new Order(
            10,
            OrderSide.BUY,
            new BigDecimal("180"),
            5
    );

    exchange.submitOrder("AAPL", appleBuy);
    exchange.submitOrder("AAPL", appleSell);
    exchange.submitOrder("NVDA", nvidiaBuy);

    boolean amended = exchange.amendOrder(
            "AAPL",
            10,
            new BigDecimal("101"),
            5
    );

    assertTrue(amended);

    assertEquals(1, exchange.getTradeCount("AAPL"));
    assertEquals(0, exchange.getTradeCount("NVDA"));

    assertEquals(1, exchange.getOpenOrderCount("AAPL"));
    assertEquals(1, exchange.getOpenOrderCount("NVDA"));

    boolean cancelled = exchange.cancelOrder("NVDA", 10);

    assertTrue(cancelled);

    assertEquals(1, exchange.getOpenOrderCount("AAPL"));
    assertEquals(0, exchange.getOpenOrderCount("NVDA"));
}

@Test
void marketStatisticsAreIndependentPerStock() {
    Exchange exchange = new Exchange();

    exchange.registerStock(new Stock(
            "AAPL",
            "Apple Inc.",
            new BigDecimal("250.00")
    ));

    exchange.registerStock(new Stock(
            "NVDA",
            "NVIDIA Corp.",
            new BigDecimal("190.00")
    ));

    exchange.submitOrder(
            "AAPL",
            new Order(
                    1,
                    OrderSide.BUY,
                    new BigDecimal("100"),
                    10
            )
    );

    exchange.submitOrder(
            "AAPL",
            new Order(
                    2,
                    OrderSide.SELL,
                    new BigDecimal("105"),
                    5
            )
    );

    exchange.submitOrder(
            "NVDA",
            new Order(
                    1,
                    OrderSide.BUY,
                    new BigDecimal("200"),
                    4
            )
    );

    exchange.submitOrder(
            "NVDA",
            new Order(
                    2,
                    OrderSide.SELL,
                    new BigDecimal("199"),
                    4
            )
    );

    assertEquals(new BigDecimal("100"), exchange.getBestBidPrice("AAPL"));
    assertEquals(new BigDecimal("105"), exchange.getBestAskPrice("AAPL"));
    assertEquals(new BigDecimal("5"), exchange.getSpread("AAPL"));

    assertEquals(0, exchange.getTradeCount("AAPL"));
    assertEquals(0, exchange.getTotalTradedVolume("AAPL"));
    assertEquals(0, exchange.getTradeHistory("AAPL").size());

    assertEquals(1, exchange.getTradeCount("NVDA"));
    assertEquals(4, exchange.getTotalTradedVolume("NVDA"));
    assertEquals(1, exchange.getTradeHistory("NVDA").size());

    assertNull(exchange.getBestBidPrice("NVDA"));
    assertNull(exchange.getBestAskPrice("NVDA"));
    assertNull(exchange.getSpread("NVDA"));
}


    @Test
    void lastTradedPriceUpdatesOnlyAfterTrade() {
        Exchange exchange = new Exchange();

        exchange.registerStock(new Stock(
                "AAPL",
                "Apple Inc.",
                new BigDecimal("250.00")
        ));

        assertNull(exchange.getLastTradedPrice("AAPL"));

        exchange.submitOrder(
                "AAPL",
                new Order(
                        1,
                        OrderSide.BUY,
                        new BigDecimal("249"),
                        5
                )
        );

        exchange.submitOrder(
                "AAPL",
                new Order(
                        2,
                        OrderSide.SELL,
                        new BigDecimal("251"),
                        5
                )
        );

        assertNull(exchange.getLastTradedPrice("AAPL"));

        exchange.submitOrder(
                "AAPL",
                new Order(
                        3,
                        OrderSide.BUY,
                        new BigDecimal("252"),
                        5
                )
        );

        assertEquals(
                new BigDecimal("251"),
                exchange.getLastTradedPrice("AAPL")
        );
    }

    @Test
void lastTradedPricesUpdateIndependentlyPerStock() {
    Exchange exchange = new Exchange();

    exchange.registerStock(new Stock(
            "AAPL",
            "Apple Inc.",
            new BigDecimal("250.00")
    ));

    exchange.registerStock(new Stock(
            "NVDA",
            "NVIDIA Corp.",
            new BigDecimal("190.00")
    ));

    // First AAPL trade executes at the resting SELL price: 251
    exchange.submitOrder(
            "AAPL",
            new Order(
                    1,
                    OrderSide.SELL,
                    new BigDecimal("251"),
                    2
            )
    );

    exchange.submitOrder(
            "AAPL",
            new Order(
                    2,
                    OrderSide.BUY,
                    new BigDecimal("252"),
                    2
            )
    );

    assertEquals(
            new BigDecimal("251"),
            exchange.getLastTradedPrice("AAPL")
    );

    assertNull(exchange.getLastTradedPrice("NVDA"));

    // Second AAPL trade becomes the new last traded price
    exchange.submitOrder(
            "AAPL",
            new Order(
                    3,
                    OrderSide.SELL,
                    new BigDecimal("253"),
                    1
            )
    );

    exchange.submitOrder(
            "AAPL",
            new Order(
                    4,
                    OrderSide.BUY,
                    new BigDecimal("254"),
                    1
            )
    );

    assertEquals(
            new BigDecimal("253"),
            exchange.getLastTradedPrice("AAPL")
    );

    // NVDA now trades independently
    exchange.submitOrder(
            "NVDA",
            new Order(
                    1,
                    OrderSide.SELL,
                    new BigDecimal("191"),
                    1
            )
    );

    exchange.submitOrder(
            "NVDA",
            new Order(
                    2,
                    OrderSide.BUY,
                    new BigDecimal("192"),
                    1
            )
    );

    assertEquals(
            new BigDecimal("191"),
            exchange.getLastTradedPrice("NVDA")
    );

    // NVDA trading must not change AAPL's price
    assertEquals(
            new BigDecimal("253"),
            exchange.getLastTradedPrice("AAPL")
    );
}

@Test
void amendmentTriggeredTradeUpdatesLastTradedPrice() {
    Exchange exchange = new Exchange();

    exchange.registerStock(new Stock(
            "AAPL",
            "Apple Inc.",
            new BigDecimal("250.00")
    ));

    exchange.submitOrder(
            "AAPL",
            new Order(
                    1,
                    OrderSide.BUY,
                    new BigDecimal("95"),
                    5
            )
    );

    exchange.submitOrder(
            "AAPL",
            new Order(
                    2,
                    OrderSide.SELL,
                    new BigDecimal("100"),
                    3
            )
    );

    assertNull(exchange.getLastTradedPrice("AAPL"));

    boolean amended = exchange.amendOrder(
            "AAPL",
            1,
            new BigDecimal("101"),
            5
    );

    assertTrue(amended);

    assertEquals(
            new BigDecimal("100"),
            exchange.getLastTradedPrice("AAPL")
    );

    assertEquals(1, exchange.getTradeCount("AAPL"));
    assertEquals(3, exchange.getTotalTradedVolume("AAPL"));
}

    @Test
    void marketBuyConsumesLowestAsksAcrossPriceLevels() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order sellOne = new Order(
                2001,
                OrderSide.SELL,
                new BigDecimal("100"),
                2
        );

        Order sellTwo = new Order(
                2002,
                OrderSide.SELL,
                new BigDecimal("101"),
                3
        );

        engine.submitOrder(sellOne);
        engine.submitOrder(sellTwo);

        Order marketBuy = new Order(
                2003,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                4
        );

        engine.submitOrder(marketBuy);

        List<Trade> trades =
                engine.getTradeHistory();

        assertEquals(2, trades.size());

        assertEquals(
                new BigDecimal("100"),
                trades.get(0).getExecutionPrice()
        );
        assertEquals(2, trades.get(0).getQuantity());

        assertEquals(
                new BigDecimal("101"),
                trades.get(1).getExecutionPrice()
        );
        assertEquals(2, trades.get(1).getQuantity());

        assertTrue(marketBuy.isFilled());

        assertEquals(
                1,
                sellTwo.getRemainingQuantity()
        );

        assertEquals(
                new BigDecimal("101"),
                engine.getBestAskPrice()
        );

        assertEquals(1, engine.getOpenOrderCount());
    }

    @Test
    void marketSellConsumesHighestBidsAcrossPriceLevels() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order buyOne = new Order(
                2101,
                OrderSide.BUY,
                new BigDecimal("105"),
                2
        );

        Order buyTwo = new Order(
                2102,
                OrderSide.BUY,
                new BigDecimal("104"),
                3
        );

        engine.submitOrder(buyOne);
        engine.submitOrder(buyTwo);

        Order marketSell = new Order(
                2103,
                OrderSide.SELL,
                OrderType.MARKET,
                null,
                4
        );

        engine.submitOrder(marketSell);

        List<Trade> trades =
                engine.getTradeHistory();

        assertEquals(2, trades.size());

        assertEquals(
                new BigDecimal("105"),
                trades.get(0).getExecutionPrice()
        );
        assertEquals(2, trades.get(0).getQuantity());

        assertEquals(
                new BigDecimal("104"),
                trades.get(1).getExecutionPrice()
        );
        assertEquals(2, trades.get(1).getQuantity());

        assertTrue(marketSell.isFilled());

        assertEquals(
                1,
                buyTwo.getRemainingQuantity()
        );

        assertEquals(
                new BigDecimal("104"),
                engine.getBestBidPrice()
        );
    }

    @Test
    void unfilledMarketOrderRemainderDoesNotRestInBook() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(
                new Order(
                        2201,
                        OrderSide.SELL,
                        new BigDecimal("100"),
                        2
                )
        );

        Order marketBuy = new Order(
                2202,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertEquals(1, engine.getTradeCount());
        assertEquals(3, marketBuy.getRemainingQuantity());

        assertEquals(0, engine.getOpenOrderCount());

        assertNull(engine.getBestBidPrice());
        assertNull(engine.getBestAskPrice());

        assertFalse(engine.cancelOrder(2202));
    }

    @Test
    void marketOrderWithNoLiquidityExecutesNothing() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order marketBuy = new Order(
                2301,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertEquals(0, engine.getTradeCount());
        assertEquals(0, engine.getOpenOrderCount());
        assertEquals(5, marketBuy.getRemainingQuantity());
    }

    @Test
    void marketOrderUpdatesLastTradedPriceToFinalExecution() {
        Exchange exchange = new Exchange();

        exchange.registerStock(
                new Stock(
                        "AAPL",
                        "Apple Inc.",
                        new BigDecimal("250.00")
                )
        );

        exchange.submitOrder(
                "AAPL",
                new Order(
                        2401,
                        OrderSide.SELL,
                        new BigDecimal("100"),
                        2
                )
        );

        exchange.submitOrder(
                "AAPL",
                new Order(
                        2402,
                        OrderSide.SELL,
                        new BigDecimal("101"),
                        3
                )
        );

        exchange.submitOrder(
                "AAPL",
                new Order(
                        2403,
                        OrderSide.BUY,
                        OrderType.MARKET,
                        null,
                        5
                )
        );

        assertEquals(2, exchange.getTradeCount("AAPL"));
        assertEquals(5, exchange.getTotalTradedVolume("AAPL"));

        assertEquals(
                new BigDecimal("101"),
                exchange.getLastTradedPrice("AAPL")
        );

        assertEquals(0, exchange.getOpenOrderCount("AAPL"));
    }

    @Test
    void newOrderStartsOpen() {
        Order order = new Order(
                3001,
                OrderSide.BUY,
                new BigDecimal("100"),
                10
        );

        assertEquals(OrderStatus.OPEN, order.getStatus());
        assertEquals(0, order.getFilledQuantity());
        assertEquals(10, order.getRemainingQuantity());
        assertTrue(order.isActive());
        assertFalse(order.isFilled());
        assertFalse(order.isCancelled());
    }

    @Test
    void partialFillUpdatesOrderStatus() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order sell = new Order(
                3101,
                OrderSide.SELL,
                new BigDecimal("100"),
                10
        );

        Order buy = new Order(
                3102,
                OrderSide.BUY,
                new BigDecimal("100"),
                4
        );

        engine.submitOrder(sell);
        engine.submitOrder(buy);

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                sell.getStatus()
        );

        assertEquals(4, sell.getFilledQuantity());
        assertEquals(6, sell.getRemainingQuantity());
        assertTrue(sell.isActive());

        assertEquals(OrderStatus.FILLED, buy.getStatus());
    }

    @Test
    void fullFillUpdatesOrderStatus() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order sell = new Order(
                3201,
                OrderSide.SELL,
                new BigDecimal("100"),
                5
        );

        Order buy = new Order(
                3202,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        );

        engine.submitOrder(sell);
        engine.submitOrder(buy);

        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(OrderStatus.FILLED, buy.getStatus());

        assertEquals(5, sell.getFilledQuantity());
        assertEquals(5, buy.getFilledQuantity());

        assertEquals(0, sell.getRemainingQuantity());
        assertEquals(0, buy.getRemainingQuantity());

        assertFalse(sell.isActive());
        assertFalse(buy.isActive());
    }

    @Test
    void cancelledLimitOrderUpdatesStatus() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order buy = new Order(
                3301,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        );

        engine.submitOrder(buy);

        assertEquals(OrderStatus.OPEN, buy.getStatus());

        assertTrue(engine.cancelOrder(3301));

        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertTrue(buy.isCancelled());
        assertFalse(buy.isActive());

        assertEquals(0, buy.getFilledQuantity());
        assertEquals(5, buy.getRemainingQuantity());

        assertFalse(engine.cancelOrder(3301));
    }

    @Test
    void partiallyFilledLimitOrderCanBeCancelled() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order sell = new Order(
                3401,
                OrderSide.SELL,
                new BigDecimal("100"),
                10
        );

        engine.submitOrder(sell);

        engine.submitOrder(
                new Order(
                        3402,
                        OrderSide.BUY,
                        new BigDecimal("100"),
                        4
                )
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                sell.getStatus()
        );

        assertEquals(4, sell.getFilledQuantity());
        assertEquals(6, sell.getRemainingQuantity());

        assertTrue(engine.cancelOrder(3401));

        assertEquals(
                OrderStatus.CANCELLED,
                sell.getStatus()
        );

        assertEquals(4, sell.getFilledQuantity());
        assertEquals(6, sell.getRemainingQuantity());
        assertFalse(sell.isActive());
    }

    @Test
    void partiallyFilledMarketOrderCancelsRemainder() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(
                new Order(
                        3501,
                        OrderSide.SELL,
                        new BigDecimal("100"),
                        2
                )
        );

        Order marketBuy = new Order(
                3502,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertEquals(
                OrderStatus.CANCELLED,
                marketBuy.getStatus()
        );

        assertEquals(2, marketBuy.getFilledQuantity());
        assertEquals(3, marketBuy.getRemainingQuantity());

        assertTrue(marketBuy.isCancelled());
        assertFalse(marketBuy.isActive());
    }

    @Test
    void marketOrderWithNoLiquidityEndsCancelled() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order marketBuy = new Order(
                3601,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertEquals(
                OrderStatus.CANCELLED,
                marketBuy.getStatus()
        );

        assertEquals(0, marketBuy.getFilledQuantity());
        assertEquals(5, marketBuy.getRemainingQuantity());

        assertEquals(0, engine.getOpenOrderCount());
    }

    @Test
    void fullyFilledMarketOrderEndsFilled() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(
                new Order(
                        3701,
                        OrderSide.SELL,
                        new BigDecimal("100"),
                        5
                )
        );

        Order marketBuy = new Order(
                3702,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertEquals(
                OrderStatus.FILLED,
                marketBuy.getStatus()
        );

        assertEquals(5, marketBuy.getFilledQuantity());
        assertEquals(0, marketBuy.getRemainingQuantity());

        assertTrue(marketBuy.isFilled());
        assertFalse(marketBuy.isCancelled());
    }

    @Test
    void openOrderCanBeLookedUpById() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order order = new Order(
                4001,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        );

        engine.submitOrder(order);

        assertSame(
                order,
                engine.getOpenOrder(4001)
        );

        assertNull(
                engine.getOpenOrder(9999)
        );
    }

    @Test
    void filledOrderIsRemovedFromOpenOrderIndex() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order sell = new Order(
                4101,
                OrderSide.SELL,
                new BigDecimal("100"),
                5
        );

        Order buy = new Order(
                4102,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        );

        engine.submitOrder(sell);
        engine.submitOrder(buy);

        assertTrue(sell.isFilled());
        assertTrue(buy.isFilled());

        assertNull(engine.getOpenOrder(4101));
        assertNull(engine.getOpenOrder(4102));

        assertEquals(0, engine.getOpenOrderCount());
    }

    @Test
    void cancelledOrderIsRemovedFromOpenOrderIndex() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order order = new Order(
                4201,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        );

        engine.submitOrder(order);

        assertSame(
                order,
                engine.getOpenOrder(4201)
        );

        assertTrue(engine.cancelOrder(4201));

        assertNull(
                engine.getOpenOrder(4201)
        );

        assertEquals(
                OrderStatus.CANCELLED,
                order.getStatus()
        );
    }

    @Test
    void amendedOrderRemainsAvailableInOpenOrderIndex() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order order = new Order(
                4301,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        );

        engine.submitOrder(order);

        assertTrue(
                engine.amendOrder(
                        4301,
                        new BigDecimal("101"),
                        8
                )
        );

        Order lookedUp =
                engine.getOpenOrder(4301);

        assertSame(order, lookedUp);

        assertEquals(
                new BigDecimal("101"),
                lookedUp.getPrice()
        );

        assertEquals(
                8,
                lookedUp.getRemainingQuantity()
        );

        assertEquals(1, engine.getOpenOrderCount());
    }

    @Test
    void marketOrdersNeverEnterOpenOrderIndex() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        Order marketBuy = new Order(
                4401,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertNull(
                engine.getOpenOrder(4401)
        );

        assertEquals(0, engine.getOpenOrderCount());

        assertEquals(
                OrderStatus.CANCELLED,
                marketBuy.getStatus()
        );
    }

    @Test
    void openOrderLookupIsScopedByStock() {
        Exchange exchange = new Exchange();

        exchange.registerStock(
                new Stock(
                        "AAPL",
                        "Apple Inc.",
                        new BigDecimal("250")
                )
        );

        exchange.registerStock(
                new Stock(
                        "NVDA",
                        "NVIDIA Corp.",
                        new BigDecimal("190")
                )
        );

        Order appleOrder = new Order(
                5001,
                OrderSide.BUY,
                new BigDecimal("200"),
                5
        );

        Order nvidiaOrder = new Order(
                5001,
                OrderSide.SELL,
                new BigDecimal("220"),
                3
        );

        exchange.submitOrder("AAPL", appleOrder);
        exchange.submitOrder("NVDA", nvidiaOrder);

        assertSame(
                appleOrder,
                exchange.getOpenOrder("AAPL", 5001)
        );

        assertSame(
                nvidiaOrder,
                exchange.getOpenOrder("NVDA", 5001)
        );
    }

    @Test
    void orderBookRejectsDuplicateOpenOrderIds() {
        OrderBook book = new OrderBook();

        book.addOrder(
                new Order(
                        5101,
                        OrderSide.BUY,
                        new BigDecimal("100"),
                        5
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> book.addOrder(
                        new Order(
                                5101,
                                OrderSide.SELL,
                                new BigDecimal("110"),
                                5
                        )
                )
        );

        assertEquals(1, book.getOpenOrderCount());
    }

    @Test
    void marketDepthAggregatesQuantityByPriceLevel() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                6001,
                OrderSide.BUY,
                new BigDecimal("101"),
                3
        ));

        engine.submitOrder(new Order(
                6002,
                OrderSide.BUY,
                new BigDecimal("101"),
                2
        ));

        engine.submitOrder(new Order(
                6003,
                OrderSide.BUY,
                new BigDecimal("100"),
                7
        ));

        engine.submitOrder(new Order(
                6004,
                OrderSide.SELL,
                new BigDecimal("102"),
                4
        ));

        engine.submitOrder(new Order(
                6005,
                OrderSide.SELL,
                new BigDecimal("103"),
                6
        ));

        NavigableMap<BigDecimal, Long> buyDepth =
                engine.getBuyDepth();

        NavigableMap<BigDecimal, Long> sellDepth =
                engine.getSellDepth();

        assertEquals(2, buyDepth.size());
        assertEquals(5L, buyDepth.get(new BigDecimal("101")));
        assertEquals(7L, buyDepth.get(new BigDecimal("100")));

        assertEquals(
                List.of(
                        new BigDecimal("101"),
                        new BigDecimal("100")
                ),
                List.copyOf(buyDepth.keySet())
        );

        assertEquals(2, sellDepth.size());
        assertEquals(4L, sellDepth.get(new BigDecimal("102")));
        assertEquals(6L, sellDepth.get(new BigDecimal("103")));

        assertEquals(
                List.of(
                        new BigDecimal("102"),
                        new BigDecimal("103")
                ),
                List.copyOf(sellDepth.keySet())
        );
    }

    @Test
    void marketDepthReflectsRemainingQuantityAfterTrade() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                6101,
                OrderSide.SELL,
                new BigDecimal("100"),
                10
        ));

        engine.submitOrder(new Order(
                6102,
                OrderSide.BUY,
                new BigDecimal("100"),
                4
        ));

        NavigableMap<BigDecimal, Long> sellDepth =
                engine.getSellDepth();

        assertEquals(1, sellDepth.size());

        assertEquals(
                6L,
                sellDepth.get(new BigDecimal("100"))
        );

        assertTrue(engine.getBuyDepth().isEmpty());
    }

    @Test
    void marketDepthReflectsCancellationAndAmendment() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                6201,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        ));

        engine.submitOrder(new Order(
                6202,
                OrderSide.BUY,
                new BigDecimal("100"),
                3
        ));

        assertEquals(
                8L,
                engine.getBuyDepth().get(new BigDecimal("100"))
        );

        engine.cancelOrder(6202);

        assertEquals(
                5L,
                engine.getBuyDepth().get(new BigDecimal("100"))
        );

        engine.amendOrder(
                6201,
                new BigDecimal("99"),
                7
        );

        assertFalse(
                engine.getBuyDepth().containsKey(
                        new BigDecimal("100")
                )
        );

        assertEquals(
                7L,
                engine.getBuyDepth().get(
                        new BigDecimal("99")
                )
        );
    }

    @Test
    void marketDepthSnapshotsAreUnmodifiable() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                6301,
                OrderSide.BUY,
                new BigDecimal("100"),
                5
        ));

        NavigableMap<BigDecimal, Long> depth =
                engine.getBuyDepth();

        assertThrows(
                UnsupportedOperationException.class,
                () -> depth.put(
                        new BigDecimal("999"),
                        999L
                )
        );

        assertNull(
                engine.getBuyDepth().get(
                        new BigDecimal("999")
                )
        );
    }

    @Test
    void marketAnalyticsAreEmptyBeforeAnyTrades() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        assertEquals(0, engine.getTradeCount());
        assertEquals(0, engine.getTotalTradedVolume());

        assertNull(engine.getVwap());
        assertNull(engine.getAverageTradeSize());
    }

    @Test
    void calculatesVwapAcrossMultipleExecutions() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                7001,
                OrderSide.SELL,
                new BigDecimal("100"),
                2
        ));

        engine.submitOrder(new Order(
                7002,
                OrderSide.SELL,
                new BigDecimal("102"),
                3
        ));

        Order marketBuy = new Order(
                7003,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                5
        );

        engine.submitOrder(marketBuy);

        assertEquals(2, engine.getTradeCount());
        assertEquals(5, engine.getTotalTradedVolume());

        assertEquals(
                new BigDecimal("101.2000"),
                engine.getVwap()
        );

        assertEquals(
                new BigDecimal("2.50"),
                engine.getAverageTradeSize()
        );
    }

    @Test
    void vwapWeightsPricesByExecutedQuantity() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                7101,
                OrderSide.SELL,
                new BigDecimal("100"),
                9
        ));

        engine.submitOrder(new Order(
                7102,
                OrderSide.SELL,
                new BigDecimal("110"),
                1
        ));

        engine.submitOrder(new Order(
                7103,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                10
        ));

        assertEquals(10, engine.getTotalTradedVolume());

        assertEquals(
                new BigDecimal("101.0000"),
                engine.getVwap()
        );
    }

    @Test
    void marketAnalyticsAreIndependentPerStock() {
        Exchange exchange = new Exchange();

        exchange.registerStock(new Stock(
                "AAPL",
                "Apple Inc.",
                new BigDecimal("250")
        ));

        exchange.registerStock(new Stock(
                "NVDA",
                "NVIDIA Corp.",
                new BigDecimal("190")
        ));

        exchange.submitOrder(
                "AAPL",
                new Order(
                        7201,
                        OrderSide.SELL,
                        new BigDecimal("100"),
                        5
                )
        );

        exchange.submitOrder(
                "AAPL",
                new Order(
                        7202,
                        OrderSide.BUY,
                        new BigDecimal("100"),
                        5
                )
        );

        assertEquals(1, exchange.getTradeCount("AAPL"));
        assertEquals(5, exchange.getTotalTradedVolume("AAPL"));

        assertEquals(
                new BigDecimal("100.0000"),
                exchange.getVwap("AAPL")
        );

        assertEquals(0, exchange.getTradeCount("NVDA"));
        assertEquals(0, exchange.getTotalTradedVolume("NVDA"));
        assertNull(exchange.getVwap("NVDA"));
    }

    @Test
    void cancellationsAndNonCrossingOrdersDoNotAffectAnalytics() {
        MatchingEngine engine =
                new MatchingEngine(new OrderBook());

        engine.submitOrder(new Order(
                7301,
                OrderSide.BUY,
                new BigDecimal("90"),
                5
        ));

        engine.submitOrder(new Order(
                7302,
                OrderSide.SELL,
                new BigDecimal("110"),
                5
        ));

        engine.cancelOrder(7301);

        assertEquals(0, engine.getTradeCount());
        assertEquals(0, engine.getTotalTradedVolume());
        assertNull(engine.getVwap());
        assertNull(engine.getAverageTradeSize());
    }

}
