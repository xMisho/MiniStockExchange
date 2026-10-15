package com.misho.exchange;

import java.math.BigDecimal;
import java.util.List;
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
}
