package MiniStockExchange;

public class MatchingEngine {

    private final OrderBook orderBook;

    public MatchingEngine(OrderBook orderBook) {
        if (orderBook == null) {
            throw new IllegalArgumentException("Order book must not be null.");
        }
        this.orderBook = orderBook;
    }

    public void submitOrder(Order order) {
        orderBook.addOrder(order);
        matchOrders();
    }

    private void matchOrders() {
        orderBook.removeFilledOrders();
        while (orderBook.canMatch()) {
            Order buy = orderBook.getBestBuyOrder();
            Order sell = orderBook.getBestSellOrder();
            int quantity = Math.min(buy.getRemainingQuantity(), sell.getRemainingQuantity());

            buy.fill(quantity);
            sell.fill(quantity);
            orderBook.removeFilledOrders();
        }
    }
}
