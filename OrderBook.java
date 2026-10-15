package MiniStockExchange;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Stores open orders; concurrent access must go through its owning MatchingEngine. */
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

        orders.computeIfAbsent(order.getPrice(), price -> new ArrayDeque<>())
                .addLast(order);
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

    public boolean cancelOrder(long orderId) {
        if (cancelOrder(buyOrders, orderId)) {
            return true;
        }
        return cancelOrder(sellOrders, orderId);
    }

    private boolean cancelOrder(NavigableMap<BigDecimal, Deque<Order>> orders,
                                long orderId) {
        Iterator<Map.Entry<BigDecimal, Deque<Order>>> priceIterator =
                orders.entrySet().iterator();

        while (priceIterator.hasNext()) {
            Deque<Order> queue = priceIterator.next().getValue();
            Iterator<Order> orderIterator = queue.iterator();

            while (orderIterator.hasNext()) {
                if (orderIterator.next().getOrderId() == orderId) {
                    orderIterator.remove();
                    if (queue.isEmpty()) {
                        priceIterator.remove();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public BigDecimal getBestBidPrice() {
        Order bestBuy = getBestBuyOrder();
        return bestBuy == null ? null : bestBuy.getPrice();
    }

    public BigDecimal getBestAskPrice() {
        Order bestSell = getBestSellOrder();
        return bestSell == null ? null : bestSell.getPrice();
    }

    public BigDecimal getSpread() {
        BigDecimal bestBid = getBestBidPrice();
        BigDecimal bestAsk = getBestAskPrice();
        if (bestBid == null || bestAsk == null) {
            return null;
        }
        return bestAsk.subtract(bestBid);
    }

    private int getOrderCount(NavigableMap<BigDecimal, Deque<Order>> orders) {
        int count = 0;
        for (Deque<Order> queue : orders.values()) {
            count += queue.size();
        }
        return count;
    }

    public int getOpenOrderCount() {
        return getOrderCount(buyOrders) + getOrderCount(sellOrders);
    }

    public int getBuyOrderCount() {
        return getOrderCount(buyOrders);
    }

    public int getSellOrderCount() {
        return getOrderCount(sellOrders);
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
