package com.misho.exchange;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Stores open orders; concurrent access must go through its owning MatchingEngine. */
public class OrderBook {

    private final NavigableMap<BigDecimal, Deque<Order>> buyOrders;
    private final NavigableMap<BigDecimal, Deque<Order>> sellOrders;
    private final Map<Long, Order> openOrdersById;

    public OrderBook() {
        this.buyOrders = new TreeMap<>(java.util.Collections.reverseOrder());
        this.sellOrders = new TreeMap<>();
        this.openOrdersById = new HashMap<>();
    }

    public void addOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order must not be null.");
        }
        if (order.getOrderType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("Only limit orders can rest in the order book.");
        }
        if (openOrdersById.containsKey(order.getOrderId())) {
            throw new IllegalArgumentException("Order ID is already open in this order book.");
        }
        NavigableMap<BigDecimal, Deque<Order>> orders =
                order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;

        orders.computeIfAbsent(order.getPrice(), price -> new ArrayDeque<>())
                .addLast(order);
        openOrdersById.put(order.getOrderId(), order);
    }

    Order findOrder(long orderId) {
        return openOrdersById.get(orderId);
    }

    Order amendOrder(
            long orderId,
            BigDecimal newPrice,
            int newRemainingQuantity) {

        Order order = findOrder(orderId);

        if (order == null) {
            return null;
        }

        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive.");
        }
        if (newRemainingQuantity <= 0) {
            throw new IllegalArgumentException("Remaining quantity must be positive.");
        }

        boolean samePrice =
                order.getPrice().compareTo(newPrice) == 0;

        int oldRemainingQuantity =
                order.getRemainingQuantity();

        if (samePrice && newRemainingQuantity <= oldRemainingQuantity) {
            order.amend(newPrice, newRemainingQuantity);
            return order;
        }

        removeOrder(order);
        order.amend(newPrice, newRemainingQuantity);
        addOrder(order);

        return order;
    }

    private boolean removeOrder(Order order) {
        NavigableMap<BigDecimal, Deque<Order>> orders =
                order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;

        Deque<Order> queue = orders.get(order.getPrice());

        if (queue == null) {
            return false;
        }

        boolean removed = queue.remove(order);
        if (!removed) {
            return false;
        }

        openOrdersById.remove(order.getOrderId(), order);

        if (queue.isEmpty()) {
            orders.remove(order.getPrice());
        }

        return true;
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
                Order filledOrder = queue.removeFirst();
                openOrdersById.remove(filledOrder.getOrderId(), filledOrder);
            }
            if (queue.isEmpty()) {
                orders.pollFirstEntry();
            } else {
                break;
            }
        }
    }

    public boolean cancelOrder(long orderId) {
        Order order = openOrdersById.get(orderId);
        if (order == null) {
            return false;
        }
        if (!removeOrder(order)) {
            return false;
        }

        order.cancel();
        return true;
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
        return openOrdersById.size();
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
