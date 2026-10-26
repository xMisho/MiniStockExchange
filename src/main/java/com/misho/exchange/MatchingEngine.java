package com.misho.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;


public class MatchingEngine {

    private static final long CANDLE_MILLIS = 60_000L;

    private final OrderBook orderBook;
    private final Stock stock;
    private final List<Trade> tradeHistory;
    private final Set<Long> usedOrderIds;
    private long totalTradedVolume;
    private BigDecimal totalTradedNotional;
    private final Clock clock;
    private final NavigableMap<Long, OhlcCandle> ohlcCandles;


    public MatchingEngine(OrderBook orderBook) {
        this(orderBook, null);
    }

    public MatchingEngine(OrderBook orderBook, Stock stock) {
        this(orderBook, stock, Clock.systemUTC());
    }

    MatchingEngine(OrderBook orderBook, Stock stock, Clock clock) {
        if (orderBook == null) {
            throw new IllegalArgumentException("Order book must not be null.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null.");
        }
        this.clock = clock;
        this.ohlcCandles = new TreeMap<>();
        this.orderBook = orderBook;
        this.stock = stock;
        this.tradeHistory = new ArrayList<>();
        this.usedOrderIds = new HashSet<>();
        this.totalTradedVolume = 0;
        this.totalTradedNotional = BigDecimal.ZERO;
    }

    public synchronized void submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order must not be null.");
        }
        if (!usedOrderIds.add(order.getOrderId())) {
            throw new IllegalArgumentException("Order ID has already been used.");
        }
        if (order.getOrderType() == OrderType.MARKET) {
            matchMarketOrder(order);
        } else {
            orderBook.addOrder(order);
            matchOrders(order);
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
            executeTrade(buy, sell, quantity, executionPrice);

            orderBook.removeFilledOrders();
        }
    }

    private void executeTrade(Order buy, Order sell, int quantity,
                              BigDecimal executionPrice) {
        Trade trade = new Trade(
                buy.getOrderId(),
                sell.getOrderId(),
                executionPrice,
                quantity,
                clock.millis()
        );

        buy.fill(quantity);
        sell.fill(quantity);
        tradeHistory.add(trade);
        totalTradedVolume += quantity;
        totalTradedNotional = totalTradedNotional.add(
                executionPrice.multiply(BigDecimal.valueOf(quantity))
        );

        recordOhlc(trade);

        if (stock != null) {
            stock.updateLastTradedPrice(trade.getExecutionPrice());
        }
    }

    private void matchMarketOrder(Order marketOrder) {
        orderBook.removeFilledOrders();

        while (!marketOrder.isFilled()) {
            Order restingOrder = marketOrder.getSide() == OrderSide.BUY
                    ? orderBook.getBestSellOrder()
                    : orderBook.getBestBuyOrder();

            if (restingOrder == null) {
                break;
            }

            Order buy = marketOrder.getSide() == OrderSide.BUY
                    ? marketOrder
                    : restingOrder;
            Order sell = marketOrder.getSide() == OrderSide.SELL
                    ? marketOrder
                    : restingOrder;
            int quantity = Math.min(
                    marketOrder.getRemainingQuantity(),
                    restingOrder.getRemainingQuantity()
            );
            BigDecimal executionPrice = restingOrder.getPrice();

            executeTrade(buy, sell, quantity, executionPrice);
            orderBook.removeFilledOrders();
        }

        if (!marketOrder.isFilled()) {
            marketOrder.cancel();
        }
    }

    public synchronized List<Trade> getTradeHistory() {
        return List.copyOf(tradeHistory);
    }

    public synchronized boolean cancelOrder(long orderId) {
        return orderBook.cancelOrder(orderId);
    }

    public synchronized boolean amendOrder(
            long orderId,
            BigDecimal newPrice,
            int newRemainingQuantity) {
        Order amendedOrder =
                orderBook.amendOrder(orderId, newPrice, newRemainingQuantity);

        if (amendedOrder == null) {
            return false;
        }

        matchOrders(amendedOrder);
        return true;
    }

    public synchronized int getTradeCount() {
        return tradeHistory.size();
    }

    public synchronized NavigableMap<BigDecimal, Long> getBuyDepth() {
        return orderBook.getBuyDepth();
    }

    public synchronized NavigableMap<BigDecimal, Long> getSellDepth() {
        return orderBook.getSellDepth();
    }

    public synchronized BigDecimal getBestBidPrice() {
        return orderBook.getBestBidPrice();
    }

    public synchronized BigDecimal getBestAskPrice() {
        return orderBook.getBestAskPrice();
    }

    public synchronized BigDecimal getSpread() {
        return orderBook.getSpread();
    }

    public synchronized int getBuyOrderCount() {
        return orderBook.getBuyOrderCount();
    }

    public synchronized int getSellOrderCount() {
        return orderBook.getSellOrderCount();
    }

    public synchronized Order getOpenOrder(long orderId) {
        return orderBook.findOrder(orderId);
    }

    public synchronized int getOpenOrderCount() {
        return orderBook.getOpenOrderCount();
    }

    private void recordOhlc(Trade trade) {
        long minuteStart = Math.floorDiv(trade.getTimestamp(), CANDLE_MILLIS) * CANDLE_MILLIS;
        BigDecimal price = trade.getExecutionPrice();
        OhlcCandle previous = ohlcCandles.get(minuteStart);
        OhlcCandle updated = previous == null
                ? new OhlcCandle(minuteStart, price, price, price, price)
                : new OhlcCandle(minuteStart, previous.getOpen(),
                        previous.getHigh().max(price), previous.getLow().min(price), price);
        ohlcCandles.put(minuteStart, updated);
    }

    public synchronized NavigableMap<Long, OhlcCandle> getOhlcCandles() {
        return Collections.unmodifiableNavigableMap(new TreeMap<>(ohlcCandles));
    }

    public synchronized OhlcCandle getLatestOhlcCandle() {
        return ohlcCandles.isEmpty() ? null : ohlcCandles.lastEntry().getValue();
    }

    public synchronized BigDecimal getVwap() {
        if (totalTradedVolume == 0) {
            return null;
        }
        return totalTradedNotional.divide(
                BigDecimal.valueOf(totalTradedVolume),
                4,
                RoundingMode.HALF_UP
        );
    }

    public synchronized BigDecimal getAverageTradeSize() {
        if (tradeHistory.isEmpty()) {
            return null;
        }
        return BigDecimal.valueOf(totalTradedVolume).divide(
                BigDecimal.valueOf(tradeHistory.size()),
                2,
                RoundingMode.HALF_UP
        );
    }

    public synchronized long getTotalTradedVolume() {
        return totalTradedVolume;
    }
}
