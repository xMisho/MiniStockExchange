package MiniStockExchange;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NavigableMap;
import java.util.TreeMap;

public class OrderBook {

    private final NavigableMap<BigDecimal, Deque<Order>> buyOrders;
    private final NavigableMap<BigDecimal, Deque<Order>> sellOrders;

    public OrderBook() {
        this.buyOrders = new TreeMap<>(java.util.Collections.reverseOrder());
        this.sellOrders = new TreeMap<>();
    }

    public void addOrder(Order order) {
        if (order == null) {
    throw new IllegalArgumentException("Order must not be null.");
}
    NavigableMap<BigDecimal, Deque<Order>> orders =
            order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;

    orders.computeIfAbsent(
            order.getPrice(),
            price -> new ArrayDeque<>()
    ).addLast(order);
}

private Order getBestOrder(NavigableMap<BigDecimal, Deque<Order>> orders) {
    if (orders.isEmpty()) {
        return null;
    }
    return orders.firstEntry().getValue().peekFirst();
}

public Order getBestBuyOrder() {
    return getBestOrder(buyOrders);
}

public Order getBestSellOrder() {
    return getBestOrder(sellOrders);
}

    public void removeFilledOrders() {
        removeFilledOrders(buyOrders);
        removeFilledOrders(sellOrders);
    }

    private void removeFilledOrders(NavigableMap<BigDecimal, Deque<Order>> orders) {
        while (!orders.isEmpty()) {
            Deque<Order> queue = orders.firstEntry().getValue();
            while (!queue.isEmpty() && queue.peekFirst().isFilled()) {
                queue.removeFirst();
            }
            if (queue.isEmpty()) {
                orders.pollFirstEntry();
            } else {
                break;
            }
        }
    }

    public boolean canMatch() {
        Order buy = getBestBuyOrder();
        Order sell = getBestSellOrder();

        if (buy == null || sell == null) {
            return false;
        }
        return buy.getPrice().compareTo(sell.getPrice()) >= 0;
    }

}
