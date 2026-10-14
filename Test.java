package MiniStockExchange;

import java.math.BigDecimal;
import java.util.List;

public class Test {

    public static void main(String[] args) {
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
}
