package MiniStockExchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MatchingEngine {

    private final OrderBook orderBook;
    private final List<Trade> tradeHistory;

    public MatchingEngine(OrderBook orderBook) {
        if (orderBook == null) {
            throw new IllegalArgumentException("Order book must not be null.");
        }
        this.orderBook = orderBook;
        this.tradeHistory = new ArrayList<>();
    }

    public void submitOrder(Order order) {
        orderBook.addOrder(order);
        matchOrders(order);
    }

    private void matchOrders(Order incomingOrder) {
        orderBook.removeFilledOrders();
        while (orderBook.canMatch()) {
            Order buy = orderBook.getBestBuyOrder();
            Order sell = orderBook.getBestSellOrder();
            int quantity = Math.min(buy.getRemainingQuantity(), sell.getRemainingQuantity());
            BigDecimal executionPrice = incomingOrder.getSide() == OrderSide.BUY
                    ? sell.getPrice()
                    : buy.getPrice();
            Trade trade = new Trade(
                    buy.getOrderId(),
                    sell.getOrderId(),
                    executionPrice,
                    quantity
            );

            buy.fill(quantity);
            sell.fill(quantity);
            tradeHistory.add(trade);
            orderBook.removeFilledOrders();
        }
    }

    public List<Trade> getTradeHistory() {
        return List.copyOf(tradeHistory);
    }

    public boolean cancelOrder(long orderId) {
        return orderBook.cancelOrder(orderId);
    }

    public int getTradeCount() {
        return tradeHistory.size();
    }

    public long getTotalTradedVolume() {
        long volume = 0;
        for (Trade trade : tradeHistory) {
            volume += trade.getQuantity();
        }
        return volume;
    }
}
