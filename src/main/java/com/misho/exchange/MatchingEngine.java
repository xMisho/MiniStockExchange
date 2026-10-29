package com.misho.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;


public class MatchingEngine {

    private static final long CANDLE_MILLIS = 60_000L;

    private final OrderBook orderBook;
    private final Stock stock;
    private final List<Trade> tradeHistory;
    private final Set<Long> usedOrderIds;
    private final Map<Long, Order> knownOrders = new LinkedHashMap<>();
    private long nextGeneratedOrderId = 1;
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
        if (!order.isActive()) {
            throw new IllegalArgumentException("Order must be active.");
        }
        if (!usedOrderIds.add(order.getOrderId())) {
            throw new IllegalArgumentException("Order ID has already been used.");
        }
        knownOrders.put(order.getOrderId(), order);
        if (order.getOrderType() == OrderType.MARKET) {
            matchMarketOrder(order);
        } else {
            orderBook.addOrder(order);
            matchOrders(order);
        }
    }

    synchronized Order submitGeneratedOrder(OrderSide side, OrderType type,
                                            BigDecimal price, int quantity) {
        while (usedOrderIds.contains(nextGeneratedOrderId)) {
            if (nextGeneratedOrderId == Long.MAX_VALUE) {
                throw new IllegalStateException("No generated order IDs remain.");
            }
            nextGeneratedOrderId++;
        }
        Order order = new Order(nextGeneratedOrderId, side, type, price, quantity);
        submitOrder(order);
        if (nextGeneratedOrderId < Long.MAX_VALUE) {
            nextGeneratedOrderId++;
        }
        return order;
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
        recordTrade(trade);
    }

    private void recordTrade(Trade trade) {
        tradeHistory.add(trade);
        totalTradedVolume = Math.addExact(totalTradedVolume, trade.getQuantity());
        totalTradedNotional = totalTradedNotional.add(
                trade.getExecutionPrice().multiply(BigDecimal.valueOf(trade.getQuantity())));
        recordOhlc(trade);
        if (stock != null) {
            stock.updateLastTradedPrice(trade.getExecutionPrice());
        }
    }

    public static MatchingEngine restore(Stock stock, List<OrderSnapshot> savedOrders,
                                         List<Trade> savedTrades) {
        MatchingEngine engine = new MatchingEngine(new OrderBook(), stock);
        for (OrderSnapshot snapshot : savedOrders) {
            Order order = Order.restore(snapshot);
            if (!engine.usedOrderIds.add(order.getOrderId())) {
                throw new IllegalStateException("Duplicate saved order ID.");
            }
            engine.knownOrders.put(order.getOrderId(), order);
            if (order.isActive()) {
                if (order.getOrderType() != OrderType.LIMIT) {
                    throw new IllegalStateException("Unfinished market order requires reconciliation.");
                }
                engine.orderBook.addOrder(order);
            }
        }
        Map<Long, Long> filled = new HashMap<>();
        for (Trade trade : savedTrades) {
            Order buy = engine.knownOrders.get(trade.getBuyOrderId());
            Order sell = engine.knownOrders.get(trade.getSellOrderId());
            if (buy == null || sell == null || buy.getSide() != OrderSide.BUY || sell.getSide() != OrderSide.SELL) {
                throw new IllegalStateException("Saved trade references invalid orders.");
            }
            filled.merge(buy.getOrderId(), (long) trade.getQuantity(), Math::addExact);
            filled.merge(sell.getOrderId(), (long) trade.getQuantity(), Math::addExact);
            engine.recordTrade(trade);
        }
        for (Order order : engine.knownOrders.values()) {
            if (filled.getOrDefault(order.getOrderId(), 0L) != order.getFilledQuantity()) {
                throw new IllegalStateException("Saved fills disagree with trade history.");
            }
        }
        if (engine.orderBook.canMatch()) {
            throw new IllegalStateException("Saved resting orders cross; recovery must not create trades.");
        }
        return engine;
    }

    public synchronized List<OrderSnapshot> getOrderSnapshots() {
        return knownOrders.values().stream().map(OrderSnapshot::from).toList();
    }

    public synchronized OrderSnapshot getOrderSnapshot(long orderId) {
        Order order = knownOrders.get(orderId);
        return order == null ? null : OrderSnapshot.from(order);
    }

    public synchronized List<Trade> getTradesSince(int index) {
        if (index < 0 || index > tradeHistory.size()) {
            throw new IllegalArgumentException("Invalid trade history position.");
        }
        return List.copyOf(tradeHistory.subList(index, tradeHistory.size()));
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

    public synchronized MarketSnapshot getMarketSnapshot() {
        BigDecimal lastPrice = tradeHistory.isEmpty()
                ? null : tradeHistory.get(tradeHistory.size() - 1).getExecutionPrice();
        BigDecimal change = stock == null || lastPrice == null
                ? null : lastPrice.subtract(stock.getReferencePrice());
        BigDecimal changePercent = change == null ? null
                : change.multiply(BigDecimal.valueOf(100))
                        .divide(stock.getReferencePrice(), 4, RoundingMode.HALF_UP);
        return new MarketSnapshot(
                stock == null ? null : stock.getSymbol(),
                lastPrice, change, changePercent,
                orderBook.getBestBidPrice(), orderBook.getBestAskPrice(),
                totalTradedVolume, tradeHistory.size(), orderBook.getOpenOrderCount(), getVwap(),
                getLatestOhlcCandle(), orderBook.getBuyDepth(), orderBook.getSellDepth());
    }

    public synchronized long getTotalTradedVolume() {
        return totalTradedVolume;
    }
}
