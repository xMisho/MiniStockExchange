package com.misho.exchange;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;



public class Exchange {

    private final Map<String, Stock> stocks;
    private final Map<String, MatchingEngine> engines;

    public Exchange() {
        this.stocks = new HashMap<>();
        this.engines = new HashMap<>();
    }

    public void registerStock(Stock stock) {
    if (stock == null) {
        throw new IllegalArgumentException("Stock must not be null.");
    }

    String symbol = stock.getSymbol();

    if (stocks.containsKey(symbol)) {
        throw new IllegalArgumentException("Stock symbol is already registered.");
    }

    stocks.put(symbol, stock);
    engines.put(symbol, new MatchingEngine(new OrderBook(), stock));
}

public Stock getStock(String symbol) {
    if (symbol == null || symbol.isBlank()) {
        return null;
    }

    return stocks.get(symbol.trim().toUpperCase());
}

public int getStockCount() {
    return stocks.size();
}

private MatchingEngine getEngineOrThrow(String symbol) {
    if (symbol == null || symbol.isBlank()) {
        throw new IllegalArgumentException("Stock symbol must not be blank.");
    }

    MatchingEngine engine =
            engines.get(symbol.trim().toUpperCase());

    if (engine == null) {
        throw new IllegalArgumentException("Stock is not registered.");
    }

    return engine;
}

public void submitOrder(String symbol, Order order) {
    getEngineOrThrow(symbol).submitOrder(order);
}

public int getTradeCount(String symbol) {
    return getEngineOrThrow(symbol).getTradeCount();
}

public int getOpenOrderCount(String symbol) {
    return getEngineOrThrow(symbol).getOpenOrderCount();
}

public boolean cancelOrder(String symbol, long orderId) {
    return getEngineOrThrow(symbol).cancelOrder(orderId);
}

public boolean amendOrder(
        String symbol,
        long orderId,
        BigDecimal newPrice,
        int newRemainingQuantity) {

    return getEngineOrThrow(symbol).amendOrder(
            orderId,
            newPrice,
            newRemainingQuantity
    );
}

public BigDecimal getBestBidPrice(String symbol) {
    return getEngineOrThrow(symbol).getBestBidPrice();
}

public BigDecimal getBestAskPrice(String symbol) {
    return getEngineOrThrow(symbol).getBestAskPrice();
}

public BigDecimal getSpread(String symbol) {
    return getEngineOrThrow(symbol).getSpread();
}

public long getTotalTradedVolume(String symbol) {
    return getEngineOrThrow(symbol).getTotalTradedVolume();
}

public List<Trade> getTradeHistory(String symbol) {
    return getEngineOrThrow(symbol).getTradeHistory();
}

    public BigDecimal getLastTradedPrice(String symbol) {
        Stock stock = getStock(symbol);

        if (stock == null) {
            throw new IllegalArgumentException("Stock is not registered.");
        }

        return stock.getLastTradedPrice();
    }
    public Order getOpenOrder(String symbol, long orderId) {
        return getEngineOrThrow(symbol).getOpenOrder(orderId);
    }

    public NavigableMap<BigDecimal, Long> getBuyDepth(String symbol) {
        return getEngineOrThrow(symbol).getBuyDepth();
    }

    public NavigableMap<BigDecimal, Long> getSellDepth(String symbol) {
        return getEngineOrThrow(symbol).getSellDepth();
    }

    public BigDecimal getVwap(String symbol) {
        return getEngineOrThrow(symbol).getVwap();
    }

    public BigDecimal getAverageTradeSize(String symbol) {
        return getEngineOrThrow(symbol).getAverageTradeSize();
    }
}
