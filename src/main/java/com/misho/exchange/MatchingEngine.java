package com.misho.exchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes exchange operations. Use one engine per book; do not mutate submitted orders externally. */
public class MatchingEngine {

    private final OrderBook orderBook;
    private final List<Trade> tradeHistory;
    private final Set<Long> usedOrderIds;
    private final ReentrantLock lock;

    public MatchingEngine(OrderBook orderBook) {
        if (orderBook == null) {
            throw new IllegalArgumentException("Order book must not be null.");
        }
        this.orderBook = orderBook;
        this.tradeHistory = new ArrayList<>();
        this.usedOrderIds = new HashSet<>();
        this.lock = new ReentrantLock();
    }

    public void submitOrder(Order order) {
        lock.lock();
        try {
            if (order == null) {
                throw new IllegalArgumentException("Order must not be null.");
            }
            if (!usedOrderIds.add(order.getOrderId())) {
                throw new IllegalArgumentException("Order ID has already been used.");
            }
            orderBook.addOrder(order);
            matchOrders(order);
        } finally {
            lock.unlock();
        }
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
        lock.lock();
        try {
            return List.copyOf(tradeHistory);
        } finally {
            lock.unlock();
        }
    }

    public boolean cancelOrder(long orderId) {
        lock.lock();
        try {
            return orderBook.cancelOrder(orderId);
        } finally {
            lock.unlock();
        }
    }

    public boolean amendOrder(
        long orderId,
        BigDecimal newPrice,
        int newRemainingQuantity) {

    lock.lock();
    try {
        Order amendedOrder =
                orderBook.amendOrder(orderId, newPrice, newRemainingQuantity);

        if (amendedOrder == null) {
            return false;
        }

        matchOrders(amendedOrder);
        return true;
    } finally {
        lock.unlock();
    }
}

    public int getTradeCount() {
        lock.lock();
        try {
            return tradeHistory.size();
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal getBestBidPrice() {
        lock.lock();
        try {
            return orderBook.getBestBidPrice();
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal getBestAskPrice() {
        lock.lock();
        try {
            return orderBook.getBestAskPrice();
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal getSpread() {
        lock.lock();
        try {
            return orderBook.getSpread();
        } finally {
            lock.unlock();
        }
    }

    public int getBuyOrderCount() {
        lock.lock();
        try {
            return orderBook.getBuyOrderCount();
        } finally {
            lock.unlock();
        }
    }

    public int getSellOrderCount() {
        lock.lock();
        try {
            return orderBook.getSellOrderCount();
        } finally {
            lock.unlock();
        }
    }

    public int getOpenOrderCount() {
        lock.lock();
        try {
            return orderBook.getOpenOrderCount();
        } finally {
            lock.unlock();
        }
    }

    public long getTotalTradedVolume() {
        lock.lock();
        try {
            long volume = 0;
            for (Trade trade : tradeHistory) {
                volume += trade.getQuantity();
            }
            return volume;
        } finally {
            lock.unlock();
        }
    }
}
